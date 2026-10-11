package br.comunicador.set

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Voz por WebRTC, através do Cloudflare Realtime (SFU).
 *
 * Cada celular abre duas conexões com o SFU: uma só para enviar a própria voz
 * e outra só para receber a voz dos outros (o padrão recomendado pela Cloudflare).
 * Quem conta para todo mundo "onde ouvir cada pessoa" é o Durable Object do canal,
 * pela mesma ligação WebSocket do modo normal (mensagens RTC_PUB e RTC_LIST).
 *
 * O WebRTC cuida de: codec Opus, cancelamento de eco e ruído, ritmo adaptativo da
 * rede (disfarça pacotes perdidos), criptografia e mistura das vozes.
 * O app continua cuidando de: botão, modo por voz, botão PTT Bluetooth, bipes.
 */
class RtcVoice(
    context: Context,
    /** Endereço do Worker, ex.: https://comunicador.sua-conta.workers.dev */
    private val api: String,
    private val code: String,
    /** Senha do canal: o Worker só fala com o SFU para quem tem a senha certa. */
    private val key: String,
    /**
     * Som do microfone a cada 10 ms (amostras e taxa). O serviço usa para o nível do modo
     * por voz e, se alguém do canal estiver sem WebRTC, para mandar uma cópia pela ponte.
     */
    private val onMicSamples: (ShortArray, Int) -> Unit,
    /** Manda uma mensagem para o canal pela ligação WebSocket. */
    private val sendSignal: (ByteArray) -> Unit,
) {
    companion object {
        const val TRACK_NAME = "voz"
        private const val STUN = "stun:stun.cloudflare.com:3478"
    }

    @Volatile var status = "Conectando a voz…"; private set
    @Volatile var ready = false; private set
    /** Mensagem RTC_PUB a reenviar quando a ligação com o canal reconecta. */
    @Volatile var publishedPacket: ByteArray? = null; private set
    /** Problema que não se resolve tentando de novo (ex.: servidor sem WebRTC configurado). */
    @Volatile var fatal: String? = null; private set

    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false

    private var factory: PeerConnectionFactory? = null
    private var adm: AudioDeviceModule? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null

    /** Uma conexão com o SFU. O sinal de "terminou de reunir endereços" é por conexão. */
    private class Conn {
        lateinit var pc: PeerConnection
        @Volatile var gathered = CountDownLatch(1)
        @Volatile var failed = false
    }

    private var pub: Conn? = null
    private var sub: Conn? = null
    private var pubSession: String? = null
    private var subSession: String? = null
    private val subscribed = HashMap<String, String>() // "sessão/trilha" -> mid na conexão de receber
    private var wanted: List<Protocol.RtcPub> = emptyList()
    private val remoteTracks = java.util.concurrent.ConcurrentHashMap<String, AudioTrack>() // mid -> voz recebida
    private var duckPriority: Set<Int> = emptySet()
    private var duckActive = false
    private var attempt = 0

    // ======================================================================
    // Ciclo de vida
    // ======================================================================

    fun start() {
        exec.execute {
            try {
                initFactory()
                connect()
            } catch (e: Exception) {
                retryLater(e)
            }
        }
    }

    /** Liga ou desliga o envio da voz (botão, modo por voz, botão PTT). */
    fun setTransmit(on: Boolean) {
        try { track?.setEnabled(on) } catch (_: Exception) {}
    }

    /** Lista de quem publicou voz no canal, vinda do Durable Object. */
    fun onPublications(list: List<Protocol.RtcPub>) {
        exec.execute {
            wanted = list
            if (ready && !closed) {
                try { reconcile() } catch (e: Exception) { retryLater(e) }
            }
        }
    }

    /**
     * Prioridade: quando alguém da direção/produção está falando, as outras vozes
     * recebidas caem para 25% do volume.
     */
    fun applyDucking(priorityIds: Set<Int>, priorityTalking: Boolean) {
        exec.execute {
            duckPriority = priorityIds
            duckActive = priorityTalking
            updateVolumes()
        }
    }

    private fun updateVolumes() {
        for (p in wanted) {
            val mid = subscribed[p.sessionId + "/" + p.trackName] ?: continue
            val t = remoteTracks[mid] ?: continue
            val volume = if (duckActive && p.id !in duckPriority) 0.25 else 1.0
            try { t.setVolume(volume) } catch (_: Exception) {}
        }
    }

    fun close() {
        closed = true
        ready = false
        exec.execute {
            teardownConnections()
            try { track?.dispose() } catch (_: Exception) {}
            try { source?.dispose() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { adm?.release() } catch (_: Exception) {}
            track = null; source = null; factory = null; adm = null
        }
        exec.shutdown()
        try { exec.awaitTermination(3, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun initFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )
        val module = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples ->
                onMicSamples(toShorts(samples.data, samples.channelCount), samples.sampleRate)
            }
            .createAudioDeviceModule()
        adm = module
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        factory = f
        source = f.createAudioSource(MediaConstraints())
        track = f.createAudioTrack(TRACK_NAME, source).apply { setEnabled(false) }
    }

    /** Abre as duas conexões com o SFU e começa a receber quem já está no canal. */
    private fun connect() {
        status = "Conectando a voz…"
        publish()
        subSession = call("/session", JSONObject()).getString("sessionId")
        sub = newConn()
        ready = true
        attempt = 0
        status = "Voz conectada (WebRTC)"
        reconcile()
    }

    private fun retryLater(e: Exception) {
        if (closed) return
        ready = false
        fatal?.let {
            status = it
            teardownConnections()
            return // não adianta tentar de novo; o serviço passa para a reserva
        }
        status = "Voz WebRTC fora do ar: ${e.message ?: "erro"}. Tentando de novo…"
        teardownConnections()
        attempt++
        val delay = minOf(30L, 2L shl minOf(attempt, 4)) // 4, 8, 16, 30 s…
        exec.schedule({
            if (!closed) {
                try { connect() } catch (ex: Exception) { retryLater(ex) }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun teardownConnections() {
        try { pub?.pc?.close() } catch (_: Exception) {}
        try { sub?.pc?.close() } catch (_: Exception) {}
        pub = null; sub = null
        pubSession = null; subSession = null
        subscribed.clear()
        remoteTracks.clear()
        publishedPacket = null
    }

    // ======================================================================
    // Enviar a própria voz
    // ======================================================================

    private fun publish() {
        val c = newConn()
        val transceiver = c.pc.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY),
        )
        val offer = createOffer(c.pc)
        setLocal(c, offer)
        val mid = transceiver.mid ?: throw IllegalStateException("sem identificador da trilha")
        val res = call("/publish", JSONObject().put("sdp", c.pc.localDescription.description).put("mid", mid))
        setRemote(c.pc, SessionDescription(SessionDescription.Type.ANSWER, res.getString("sdp")))
        val sid = res.getString("sessionId")
        pub = c
        pubSession = sid
        val packet = Protocol.rtcPub(sid, TRACK_NAME)
        publishedPacket = packet
        sendSignal(packet) // se a ligação ainda não estiver pronta, o serviço reenvia ao conectar
    }

    // ======================================================================
    // Receber a voz dos outros
    // ======================================================================

    /** Compara quem está publicando com o que já recebemos e acerta a diferença. */
    private fun reconcile() {
        val sid = subSession ?: return
        val c = sub ?: return
        val mine = pubSession
        val want = wanted.filter { it.sessionId != mine }.associateBy { it.sessionId + "/" + it.trackName }

        val remove = subscribed.filterKeys { it !in want }
        if (remove.isNotEmpty()) {
            call("/close", JSONObject().put("sessionId", sid).put("mids", JSONArray(remove.values.toList())))
            remove.keys.forEach { subscribed.remove(it) }
        }

        val add = want.filterKeys { it !in subscribed }.values.toList()
        if (add.isEmpty()) return
        val tracks = JSONArray()
        for (p in add) tracks.put(JSONObject().put("sessionId", p.sessionId).put("trackName", p.trackName))
        val res = call("/subscribe", JSONObject().put("sessionId", sid).put("tracks", tracks))

        val results = res.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until results.length()) {
            val t = results.getJSONObject(i)
            val mid = t.optString("mid", "")
            if (mid.isNotEmpty() && !t.has("errorCode")) {
                subscribed[t.getString("sessionId") + "/" + t.getString("trackName")] = mid
            }
        }
        val offer = res.optString("sdp", "")
        if (res.optBoolean("requiresImmediateRenegotiation") && offer.isNotEmpty()) {
            setRemote(c.pc, SessionDescription(SessionDescription.Type.OFFER, offer))
            val answer = createAnswer(c.pc)
            setLocal(c, answer)
            call("/answer", JSONObject().put("sessionId", sid).put("sdp", c.pc.localDescription.description))
        }
        updateVolumes()
    }

    // ======================================================================
    // Conexões e SDP (tudo nesta única thread, uma operação de cada vez)
    // ======================================================================

    private fun newConn(): Conn {
        val f = factory ?: throw IllegalStateException("WebRTC não iniciado")
        val c = Conn()
        val config = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder(STUN).createIceServer())
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
        val observer = object : PeerConnection.Observer {
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) c.gathered.countDown()
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state == PeerConnection.IceConnectionState.FAILED && !c.failed && !closed) {
                    c.failed = true
                    exec.execute { if (pub === c || sub === c) retryLater(IllegalStateException("conexão de voz caiu")) }
                }
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: RtpTransceiver) {
                // Guarda cada voz recebida pelo seu "mid", para poder abaixar o volume (prioridade).
                val mid = transceiver.mid ?: return
                val t = transceiver.receiver?.track() as? AudioTrack ?: return
                remoteTracks[mid] = t
                exec.execute { updateVolumes() }
            }
        }
        c.pc = f.createPeerConnection(config, observer) ?: throw IllegalStateException("não consegui criar a conexão")
        return c
    }

    private fun createOffer(pc: PeerConnection): SessionDescription = createSdp { pc.createOffer(it, MediaConstraints()) }

    private fun createAnswer(pc: PeerConnection): SessionDescription = createSdp { pc.createAnswer(it, MediaConstraints()) }

    private fun createSdp(run: (SdpObserver) -> Unit): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var error: String? = null
        run(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { result = sdp; latch.countDown() }
            override fun onCreateFailure(e: String?) { error = e; latch.countDown() }
            override fun onSetSuccess() {}
            override fun onSetFailure(e: String?) {}
        })
        latch.await(10, TimeUnit.SECONDS)
        return result ?: throw IllegalStateException(error ?: "tempo esgotado")
    }

    /** Aplica a descrição local e espera reunir os endereços de rede (no máximo 3 s). */
    private fun setLocal(c: Conn, sdp: SessionDescription) {
        c.gathered = CountDownLatch(1)
        applySdp { c.pc.setLocalDescription(it, sdp) }
        if (c.pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
            c.gathered.await(3, TimeUnit.SECONDS)
        }
    }

    private fun setRemote(pc: PeerConnection, sdp: SessionDescription) = applySdp { pc.setRemoteDescription(it, sdp) }

    private fun applySdp(run: (SdpObserver) -> Unit) {
        val latch = CountDownLatch(1)
        var error: String? = null
        run(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetSuccess() { latch.countDown() }
            override fun onSetFailure(e: String?) { error = e ?: "falhou"; latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("tempo esgotado")
        error?.let { throw IllegalStateException(it) }
    }

    // ======================================================================
    // Conversa com o Worker (que guarda a chave do Cloudflare Realtime)
    // ======================================================================

    private fun call(path: String, body: JSONObject): JSONObject {
        val url = URL(api.trimEnd('/') + "/rtc/" + code + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Chave", key)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (!ok) {
                val msg = json.optString("erro", "servidor respondeu ${conn.responseCode}")
                if (conn.responseCode == 501) fatal = msg
                throw IllegalStateException(msg)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    /** Bytes PCM 16 bits do microfone para amostras; se vier em estéreo, fica com um canal. */
    private fun toShorts(pcm: ByteArray, channels: Int): ShortArray {
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val all = ShortArray(bb.remaining())
        bb.get(all)
        if (channels <= 1) return all
        return ShortArray(all.size / channels) { all[it * channels] }
    }
}
