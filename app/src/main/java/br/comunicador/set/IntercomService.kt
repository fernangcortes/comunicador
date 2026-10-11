package br.comunicador.set

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Coração do app. Roda como serviço em primeiro plano (com aviso fixo na notificação),
 * o que permite manter microfone, rede e áudio funcionando com a tela desligada.
 */
class IntercomService : Service() {

    enum class Mode { PTT, VOX }

    companion object {
        const val ACTION_HOST = "br.comunicador.set.HOST"
        const val ACTION_JOIN = "br.comunicador.set.JOIN"
        const val ACTION_TOGGLE = "br.comunicador.set.TOGGLE"
        const val ACTION_STOP = "br.comunicador.set.STOP"
        const val EXTRA_NAME = "name"
        /** Endereço da central: "192.168.0.10", "udp://ip:porta" ou "wss://nome.workers.dev". */
        const val EXTRA_SERVER = "server"
        /** Código do canal (vazio no Wi-Fi local). */
        const val EXTRA_CODE = "code"
        const val EXTRA_BT_MIC = "btMic"
        const val EXTRA_KEY = "key"
        const val EXTRA_PRIORITY = "priority"

        private const val NOTIF_ID = 7
        private const val CHANNEL_ID = "comunicador"
        private const val VOX_HOLD_MS = 700L
        private const val MEMBER_TIMEOUT_MS = 6000L

        @Volatile var instance: IntercomService? = null
            private set
    }

    private class Member(val id: Int, val addr: InetSocketAddress) {
        @Volatile var name = "Sem nome"
        @Volatile var priority = false
        @Volatile var lastSeen = 0L
        @Volatile var lastAudio = 0L
        @Volatile var talking = false
        @Volatile var talkSince = 0L
    }

    // ---- estado lido pela tela ----
    @Volatile var running = false; private set
    @Volatile var isHost = false; private set
    /** "Wi-Fi local", "Servidor" ou "Cloudflare" — mostrado na tela. */
    @Volatile var routeLabel = ""; private set
    @Volatile var channelCode = ""; private set
    @Volatile var myName = ""; private set
    @Volatile var hostLabel = ""; private set
    @Volatile var connectedToHost = false; private set
    @Volatile var people: List<Protocol.Person> = emptyList(); private set
    @Volatile var transmitting = false; private set
    @Volatile var micLevel = 0.0; private set
    @Volatile var mode = Mode.PTT
    @Volatile var pttOn = false; private set
    @Volatile var voxMuted = false; private set
    @Volatile var voxThreshold = 900.0
    @Volatile var buttonHeld = false; private set
    var pttButton: PttButton? = null; private set
    /** Situação da voz no modo WebRTC (vazio nos outros modos). */
    val voiceStatus: String get() = rtc?.status ?: fallbackNote

    // ---- volta automática do WebRTC para o WebSocket ----
    /** Aviso mostrado na tela quando a voz passou para a reserva (vazio fora disso). */
    @Volatile private var fallbackNote = ""
    /** Desde quando o WebRTC está fora do ar (0 = está funcionando). */
    private var rtcDownSince = 0L
    /** Quem do canal está com a voz no WebRTC (pela lista do Durable Object). */
    @Volatile private var rtcIds: Set<Int> = emptySet()
    /** Alguém no canal está sem WebRTC: quem está no WebRTC manda também uma cópia pelo WebSocket. */
    @Volatile private var bridgeNeeded = false
    private val bridgeBuf = ShortArray(Protocol.FRAME)
    private var bridgeFill = 0
    private var bridgeSeq = 0
    private var bridgeWasTx = false

    private var rtc: RtcVoice? = null               // só no modo WebRTC
    private var channelKey = ""                      // senha do canal (vem no convite)
    @Volatile var myPriority = false; private set    // direção/produção: os outros abaixam quando eu falo
    /** true quando o servidor recusou a senha do canal. */
    @Volatile var keyRejected = false; private set
    @Volatile var codecLabel = ""; private set
    private var opusEnc: OpusEncoder? = null
    private val opusDec = OpusDecoders()
    @Volatile private var voxUntil = 0L
    private var lastTalkers: Set<Int> = emptySet()

    // ---- internos ----
    @Volatile private var myId = 0
    private var socket: DatagramSocket? = null      // só na central do celular
    private var link: Link? = null                   // só nos participantes
    private val encoder = ImaAdpcm()
    private var ticks = 0
    private val members = ConcurrentHashMap<InetSocketAddress, Member>()
    private var nextMemberId = 2
    private val mixer = Mixer()
    private var threads = mutableListOf<Thread>()
    private var timer: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var session: MediaSession? = null
    private var discovery: Discovery? = null
    private var useBtMic = false
    private lateinit var audioManager: AudioManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HOST, ACTION_JOIN -> {
                goForeground()
                if (!running) start(intent)
            }
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    // ======================================================================
    // Início e fim
    // ======================================================================

    private fun start(intent: Intent) {
        myName = intent.getStringExtra(EXTRA_NAME)?.trim().orEmpty().ifEmpty { "Sem nome" }
        useBtMic = intent.getBooleanExtra(EXTRA_BT_MIC, false)
        channelKey = intent.getStringExtra(EXTRA_KEY).orEmpty()
        myPriority = intent.getBooleanExtra(EXTRA_PRIORITY, false)
        keyRejected = false
        isHost = intent.action == ACTION_HOST
        mixer.clear()
        members.clear()

        myId = 0
        people = emptyList()

        if (isHost) {
            // Wi-Fi local: este celular é a central.
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.PORT))
                    soTimeout = 1000
                }
            } catch (e: Exception) {
                hostLabel = "Não consegui abrir a rede: ${e.message}"
                return
            }
            myId = Protocol.HOST_ID
            channelCode = ""
            routeLabel = "Wi-Fi local"
            val ips = Protocol.localAddresses()
            hostLabel = if (ips.isEmpty()) "Sem Wi-Fi. Ligue o Wi-Fi ou o hotspot." else "Central em " + ips.joinToString(" · ")
            connectedToHost = true
            discovery = Discovery(this).also { it.announce(myName, Protocol.PORT) }
        } else {
            // Participante: central no Wi-Fi local, servidor próprio ou Cloudflare.
            val server = intent.getStringExtra(EXTRA_SERVER).orEmpty().trim()
            channelCode = intent.getStringExtra(EXTRA_CODE).orEmpty().uppercase()
            val webrtc = server.startsWith("webrtc://")
            routeLabel = when {
                webrtc -> "Cloudflare WebRTC"
                server.startsWith("wss://") || server.startsWith("https://") -> "Cloudflare"
                channelCode.isNotEmpty() -> "Servidor"
                else -> "Wi-Fi local"
            }
            hostLabel = if (channelCode.isEmpty()) "Central $server" else "Canal $channelCode"
            // No modo WebRTC, a ligação WebSocket com o canal continua existindo, mas só para
            // a lista de pessoas e para dizer onde ouvir cada um. A voz vai pelo WebRTC.
            val host = server.removePrefix("webrtc://").trimEnd('/')
            val signaling = if (webrtc) "wss://$host" else server
            link = Link.create(signaling, channelCode, ::onClientPacket, ::onLinkConnected).also { it.start() }
            if (webrtc && channelCode.isNotEmpty()) {
                rtc = RtcVoice(
                    context = this,
                    api = "https://$host",
                    code = channelCode,
                    key = channelKey,
                    onMicSamples = ::onRtcSamples,
                    sendSignal = { packet -> link?.send(packet) },
                ).also { it.start() }
            }
        }

        running = true
        acquireLocks()
        if (useBtMic) enableBluetoothMic()
        setupMediaSession()
        connectPttButton()

        // Opus quando o aparelho tem codificador (Android 10+); senão, ADPCM. No WebRTC o Opus da
        // conversa vem do motor; este codificador fica para a ponte e para a reserva.
        opusEnc = OpusEncoder.createOrNull()
        fallbackNote = ""
        rtcDownSince = 0L
        rtcIds = emptySet()
        bridgeNeeded = false
        codecLabel = when {
            rtc != null -> "Opus (WebRTC)"
            opusEnc != null -> "Opus"
            else -> "ADPCM"
        }
        threads = mutableListOf(Thread(::playbackLoop, "reproducao"))
        // No modo WebRTC o microfone é do motor do WebRTC; aqui só os bipes passam pelo app.
        if (rtc == null) threads.add(Thread(::captureLoop, "captura"))
        if (isHost) threads.add(Thread(::receiveLoop, "rx"))
        threads.forEach { it.start() }

        timer = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay(::tick, 0, 1, TimeUnit.SECONDS)
        }
        updateNotification()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        link?.send(Protocol.bye())
        timer?.shutdownNow()
        timer = null
        discovery?.stopAnnouncing()
        discovery = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        link?.close()
        link = null
        rtc?.close()
        rtc = null
        opusEnc?.release()
        opusEnc = null
        opusDec.releaseAll()
        mixer.priorityIds = emptySet()
        lastTalkers = emptySet()
        threads.forEach { try { it.join(1500) } catch (_: Exception) {} }
        threads.clear()
        session?.release()
        session = null
        pttButton?.disconnect()
        pttButton = null
        buttonHeld = false
        if (useBtMic) disableBluetoothMic()
        releaseLocks()
        people = emptyList()
        transmitting = false
        pttOn = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ======================================================================
    // Falar: botão (PTT) ou por voz (VOX)
    // ======================================================================

    /** Botão do fone, notificação ou toque na tela. */
    fun toggle() {
        if (mode == Mode.PTT) setPtt(!pttOn) else setVoxMuted(!voxMuted)
    }

    fun setPtt(on: Boolean) {
        if (pttOn == on) return
        pttOn = on
        updateNotification()
    }

    fun setVoxMuted(muted: Boolean) {
        if (voxMuted == muted) return
        voxMuted = muted
        mixer.beep(if (muted) Mixer.BEEP_END else Mixer.BEEP_START)
        updateNotification()
    }

    fun changeMode(newMode: Mode) {
        if (mode == newMode) return
        pttOn = false
        voxMuted = false
        mode = newMode
        updateNotification()
    }

    // ======================================================================
    // Áudio
    // ======================================================================

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, Protocol.FRAME * 2 * 4),
            )
        } catch (e: Exception) {
            hostLabel = "Microfone indisponível"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            hostLabel = "Microfone indisponível"
            rec.release()
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } else null
        rec.startRecording()

        val frame = ShortArray(Protocol.FRAME)
        val preroll = ArrayDeque<ShortArray>()
        var seq = 0
        while (running) {
            var n = 0
            while (n < frame.size && running) {
                val r = rec.read(frame, n, frame.size - n)
                if (r <= 0) break
                n += r
            }
            if (n < frame.size) continue

            val level = Protocol.rms(frame)
            micLevel = level
            val wasTransmitting = transmitting
            val tx = updateTx(level, SystemClock.elapsedRealtime())
            if (tx && !wasTransmitting) {
                encoder.reset()
                for (f in preroll) sendAudio(f, seq++) // não corta a primeira sílaba
                preroll.clear()
            }

            if (tx) {
                sendAudio(frame.copyOf(), seq++)
            } else {
                preroll.addLast(frame.copyOf())
                if (preroll.size > 4) preroll.removeFirst()
            }
        }
        try { rec.stop() } catch (_: Exception) {}
        aec?.release()
        ns?.release()
        rec.release()
    }

    /**
     * Decide se o microfone está no ar agora (botão, modo por voz, botão PTT Bluetooth)
     * e cuida do que acontece quando começa ou para: aviso à central, bipe, notificação.
     */
    private fun updateTx(level: Double, now: Long): Boolean {
        // Botão PTT Bluetooth segurado: transmite em qualquer modo.
        val held = buttonHeld
        val tx = held || when (mode) {
            Mode.PTT -> pttOn
            Mode.VOX -> {
                if (level > voxThreshold) voxUntil = now + VOX_HOLD_MS
                !voxMuted && now < voxUntil
            }
        }
        val byButton = held || mode == Mode.PTT
        if (tx && !transmitting) {
            transmitting = true
            announceTalk(true)
            if (byButton) mixer.beep(Mixer.BEEP_START) // confirmação no seu fone
            updateNotification()
        } else if (!tx && transmitting) {
            transmitting = false
            announceTalk(false)
            if (byButton) mixer.beep(Mixer.BEEP_END)
            updateNotification()
        }
        return tx
    }

    /** Modo WebRTC: o motor do WebRTC entrega o nível do microfone a cada 10 ms. */
    private fun onRtcSamples(pcm: ShortArray, rate: Int) {
        if (!running || pcm.isEmpty()) return
        val level = Protocol.rms(pcm)
        micLevel = level
        val tx = updateTx(level, SystemClock.elapsedRealtime())
        rtc?.setTransmit(tx)
        bridge(pcm, rate, tx)
    }

    /**
     * Ponte: se alguém do canal está sem WebRTC (caiu para a reserva, ou entrou pelo modo
     * WebSocket), quem está no WebRTC manda também uma cópia da voz pelo WebSocket.
     * Quem está no WebRTC ignora essas cópias, porque já ouve pelo WebRTC.
     */
    private fun bridge(pcm: ShortArray, rate: Int, tx: Boolean) {
        if (!tx || !bridgeNeeded) { bridgeFill = 0; bridgeWasTx = false; return }
        if (!bridgeWasTx) { encoder.reset(); bridgeWasTx = true }
        // Reduz para 16 kHz (média por janela) e junta em blocos de 20 ms.
        val step = rate.toDouble() / Protocol.SAMPLE_RATE
        var pos = 0.0
        while (pos < pcm.size) {
            val a = pos.toInt()
            val b = minOf(pcm.size, (pos + step).toInt().coerceAtLeast(a + 1))
            var sum = 0
            for (i in a until b) sum += pcm[i]
            bridgeBuf[bridgeFill++] = (sum / (b - a)).toShort()
            if (bridgeFill == Protocol.FRAME) {
                sendAudio(bridgeBuf.copyOf(), bridgeSeq++)
                bridgeFill = 0
            }
            pos += step
        }
    }

    /**
     * O WebRTC não conectou (ou caiu e não voltou): a voz passa para o WebSocket do mesmo
     * servidor, sem sair do canal. Vale até a pessoa sair e entrar de novo.
     */
    private fun fallbackToWebSocket(reason: String) {
        val r = rtc ?: return
        rtc = null
        r.close() // libera o microfone do motor do WebRTC
        link?.send(Protocol.rtcUnpub()) // avisa o canal: "minha voz agora vem pelo WebSocket"
        rtcIds = emptySet()
        bridgeNeeded = false
        lastTalkers = emptySet()
        routeLabel = "Cloudflare (reserva)"
        codecLabel = if (opusEnc != null) "Opus" else "ADPCM"
        fallbackNote = "Voz pela reserva (WebSocket): $reason"
        if (running) Thread(::captureLoop, "captura").also { threads.add(it); it.start() }
        updateNotification()
    }

    private fun updateBridgeNeeded() {
        val me = myId
        bridgeNeeded = rtc != null && people.any { it.id != me && it.id !in rtcIds }
    }

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val attrs = AudioAttributes.Builder()
            .setUsage(if (useBtMic) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(Protocol.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(attrs, format, maxOf(minBuf, Protocol.FRAME * 2 * 3), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        track.play()
        val out = ShortArray(Protocol.FRAME)
        // Escrever continuamente (silêncio quando ninguém fala) mantém o ritmo e
        // deixa o app como "tocando", para o botão do fone vir para cá.
        while (running) {
            mixer.nextFrame(out)
            track.write(out, 0, out.size)
        }
        try { track.stop() } catch (_: Exception) {}
        track.release()
    }

    // ======================================================================
    // Rede
    // ======================================================================

    private fun sendAudio(pcm: ShortArray, seq: Int) {
        val id = if (isHost) Protocol.HOST_ID else 0
        val packets: List<ByteArray> = opusEnc?.let { enc ->
            try {
                enc.encode(pcm).map { Protocol.audioRaw(id, seq, Protocol.CODEC_OPUS, it) }
            } catch (_: Exception) {
                // Codificador falhou: volta para o ADPCM sem interromper a conversa.
                enc.release(); opusEnc = null; codecLabel = "ADPCM"
                null
            }
        } ?: listOf(Protocol.audio(id, seq, pcm, encoder))
        for (packet in packets) {
            if (isHost) for (m in members.values) send(packet, m.addr) else link?.send(packet)
        }
    }

    /** Descomprime um pacote de áudio (Opus ou ADPCM) e entrega ao mixer. */
    private fun playPacket(id: Int, data: ByteArray, len: Int) {
        if (Protocol.audioCodec(data, len) == Protocol.CODEC_OPUS) {
            for (f in opusDec.decode(id, data, Protocol.AUDIO_HEADER, len - Protocol.AUDIO_HEADER)) mixer.push(id, f)
        } else {
            Protocol.audioPcm(data, len)?.let { mixer.push(id, it) }
        }
    }

    /** Avisa a central que começou ou parou de falar (para a lista "quem está falando"). */
    private fun announceTalk(on: Boolean) {
        if (isHost) broadcastRoster() else link?.send(Protocol.talk(on))
    }

    // ---- participante ----

    private fun onLinkConnected() {
        link?.send(Protocol.hello(myName, channelCode, channelKey, myPriority))
        if (transmitting) link?.send(Protocol.talk(true))
        rtc?.publishedPacket?.let { link?.send(it) } // o canal esquece quem caiu; avisa de novo onde nos ouvir
        updateNotification()
    }

    private fun onClientPacket(data: ByteArray, len: Int) {
        if (len < 1) return
        when (data[0]) {
            Protocol.ROSTER -> {
                val (me, list) = Protocol.parseRoster(data, len)
                myId = me
                people = list
                updateBridgeNeeded()
                // Prioridade: quando alguém da direção/produção fala, as outras vozes abaixam.
                val prio = list.filter { it.priority }.map { it.id }.toSet()
                mixer.priorityIds = prio
                rtc?.applyDucking(
                    priorityIds = prio,
                    priorityTalking = list.any { it.priority && it.talking && it.id != me },
                )
                if (rtc != null) {
                    // No WebRTC a voz não passa pelo app, então o bipe de rádio vem da lista.
                    val talkers = list.filter { it.talking && it.id != me }.map { it.id }.toSet()
                    if ((talkers - lastTalkers).isNotEmpty()) mixer.beep(Mixer.BEEP_START)
                    else if ((lastTalkers - talkers).isNotEmpty()) mixer.beep(Mixer.BEEP_END)
                    lastTalkers = talkers
                }
            }
            Protocol.RTC_LIST -> {
                val list = Protocol.parseRtcList(data, len)
                rtcIds = list.map { it.id }.toSet()
                updateBridgeNeeded()
                rtc?.onPublications(list)
            }
            Protocol.ERROR -> {
                if (len > 1 && data[1].toInt() == Protocol.ERR_WRONG_KEY && !keyRejected) {
                    keyRejected = true
                    hostLabel = "Senha do canal errada. Entre pelo link do convite."
                    updateNotification()
                }
            }
            Protocol.AUDIO -> {
                if (len <= Protocol.AUDIO_HEADER) return
                val id = Protocol.audioId(data)
                // No WebRTC, quem também está no WebRTC já é ouvido por lá: ignora a cópia da ponte.
                if (id != myId && !(rtc != null && id in rtcIds)) playPacket(id, data, len)
            }
        }
    }

    // ---- central no celular (Wi-Fi local) ----

    private fun send(data: ByteArray, to: InetSocketAddress) {
        try { socket?.send(DatagramPacket(data, data.size, to)) } catch (_: Exception) {}
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                packet.length = buf.size
                socket?.receive(packet) ?: return
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!running) return else continue
            }
            val len = packet.length
            if (len < 1) continue
            val from = packet.socketAddress as InetSocketAddress
            onHostPacket(buf, len, from)
        }
    }

    private fun onHostPacket(data: ByteArray, len: Int, from: InetSocketAddress) {
        val now = SystemClock.elapsedRealtime()
        when (data[0]) {
            Protocol.HELLO -> {
                var isNew = false
                val m = members.getOrPut(from) {
                    isNew = true
                    Member(nextMemberId, from).also { nextMemberId = if (nextMemberId >= 65000) 2 else nextMemberId + 1 }
                }
                val h = Protocol.parseHello(data, len)
                if (h.name != m.name) { m.name = h.name; isNew = true }
                if (h.priority != m.priority) { m.priority = h.priority; isNew = true }
                m.lastSeen = now
                if (isNew) broadcastRoster()
            }
            Protocol.TALK -> {
                val m = members[from] ?: return
                m.lastSeen = now
                m.talking = len > 1 && data[1].toInt() != 0
                if (m.talking) m.talkSince = now
                broadcastRoster()
            }
            Protocol.AUDIO -> {
                val m = members[from] ?: return
                if (len <= Protocol.AUDIO_HEADER) return
                m.lastSeen = now
                m.lastAudio = now
                val forward = Protocol.withId(data, len, m.id)
                for (other in members.values) if (other !== m) send(forward, other.addr)
                playPacket(m.id, data, len)
            }
            Protocol.BYE -> {
                if (members.remove(from) != null) broadcastRoster()
            }
        }
    }

    private fun currentRoster(): List<Protocol.Person> {
        val now = SystemClock.elapsedRealtime()
        val list = ArrayList<Protocol.Person>()
        list.add(Protocol.Person(Protocol.HOST_ID, myName, transmitting, myPriority))
        for (m in members.values.sortedBy { it.id }) {
            // Se o aviso de "parei" se perder no Wi-Fi, a falta de áudio encerra a fala.
            val talking = m.talking && (now - m.lastAudio < 1500 || now - m.talkSince < 1500)
            list.add(Protocol.Person(m.id, m.name, talking, m.priority))
        }
        mixer.priorityIds = list.filter { it.priority && it.id != Protocol.HOST_ID }.map { it.id }.toSet()
        return list
    }

    private fun broadcastRoster() {
        val list = currentRoster()
        people = list
        for (m in members.values) send(Protocol.roster(m.id, list), m.addr)
    }

    /** Uma vez por segundo: presença, lista de pessoas e verificação de conexão. */
    private fun tick() {
        if (!running) return
        ticks++
        if (ticks % 30 == 0) opusDec.cleanup()
        val now = SystemClock.elapsedRealtime()
        // Volta automática: WebRTC sem configuração no servidor, ou fora do ar por 20 s.
        rtc?.let { r ->
            if (r.ready) rtcDownSince = 0L else if (rtcDownSince == 0L) rtcDownSince = now
            val fatal = r.fatal
            when {
                fatal != null -> fallbackToWebSocket(fatal)
                rtcDownSince != 0L && now - rtcDownSince > 20_000 -> fallbackToWebSocket("o WebRTC não conectou nesta rede")
            }
        }
        if (isHost) {
            members.entries.removeIf { now - it.value.lastSeen > MEMBER_TIMEOUT_MS }
            broadcastRoster()
        } else {
            val l = link ?: return
            if (l.helloKeepalive) {
                if (ticks % 2 == 0) l.send(Protocol.hello(myName, channelCode, channelKey, myPriority))
            } else if (ticks % 15 == 0) {
                l.keepalive()
            }
            val was = connectedToHost
            connectedToHost = l.connected
            if (!connectedToHost) people = emptyList()
            if (was != connectedToHost) updateNotification()
        }
    }

    // ======================================================================
    // Botão do fone (MediaSession)
    // ======================================================================

    private fun setupMediaSession() {
        session = MediaSession(this, "Comunicador").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    @Suppress("DEPRECATION")
                    val ev = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                    val handled = ev.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        ev.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                    if (!handled) return false
                    if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) toggle()
                    return true
                }
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    // ======================================================================
    // Botão PTT Bluetooth (segurar para falar)
    // ======================================================================

    private fun connectPttButton() {
        val prefs = getSharedPreferences("comunicador", Context.MODE_PRIVATE)
        val addr = prefs.getString("pttAddr", null) ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        pttButton = PttButton(this) { held ->
            buttonHeld = held
            updateNotification()
        }.also { it.connect(addr, prefs.getString("pttName", "") ?: "") }
    }

    // ======================================================================
    // Microfone do fone Bluetooth (modo chamada / SCO)
    // ======================================================================

    private fun enableBluetoothMic() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?.let { audioManager.setCommunicationDevice(it) }
            } else {
                @Suppress("DEPRECATION") audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
            }
        } catch (_: Exception) {}
    }

    private fun disableBluetoothMic() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    // ======================================================================
    // Manter acordado: CPU e Wi-Fi
    // ======================================================================

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "comunicador:audio").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiMode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(wifiMode, "comunicador:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    // ======================================================================
    // Notificação fixa (com botões na tela de bloqueio)
    // ======================================================================

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Comunicador ativo", NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setShowBadge(false)
                }
            )
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        if (!running) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val toggleIntent = PendingIntent.getService(this, 1, Intent(this, IntercomService::class.java).setAction(ACTION_TOGGLE), flags)
        val stopIntent = PendingIntent.getService(this, 2, Intent(this, IntercomService::class.java).setAction(ACTION_STOP), flags)

        val title = when {
            transmitting -> "No ar"
            !connectedToHost -> if (channelCode.isEmpty()) "Procurando a central…" else "Reconectando…"
            isHost -> "Central do canal"
            channelCode.isNotEmpty() -> "Canal $channelCode · $routeLabel"
            else -> "No canal"
        }
        val text = when (mode) {
            Mode.PTT -> if (pttOn) "Botão: falando" else "Botão: toque no fone para falar"
            Mode.VOX -> if (voxMuted) "Por voz: microfone silenciado" else "Por voz: microfone aberto"
        }
        val actionLabel = when (mode) {
            Mode.PTT -> if (pttOn) "Parar de falar" else "Falar"
            Mode.VOX -> if (voxMuted) "Abrir microfone" else "Silenciar"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_comunicador)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(Notification.Action.Builder(null, actionLabel, toggleIntent).build())
            .addAction(Notification.Action.Builder(null, "Sair", stopIntent).build())
            .build()
    }
}
