package br.comunicador.set

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // Paleta: luz de tally (vermelho = no ar, âmbar = alguém falando)
    private val bg = Color.parseColor("#22262B")
    private val panel = Color.parseColor("#2D3238")
    private val line = Color.parseColor("#3E454D")
    private val fg = Color.parseColor("#EEF1F3")
    private val muted = Color.parseColor("#98A2AD")
    private val tally = Color.parseColor("#E5323F")
    private val cue = Color.parseColor("#F2A516")
    private val ok = Color.parseColor("#33C07A")

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("comunicador", Context.MODE_PRIVATE) }
    private lateinit var discovery: Discovery
    private val found = LinkedHashMap<String, Discovery.Found>()

    // tela de entrada
    private lateinit var joinScreen: View
    private lateinit var nameInput: EditText
    private lateinit var btMic: CheckBox
    private lateinit var foundList: LinearLayout
    private lateinit var foundEmpty: TextView
    private lateinit var ipInput: EditText
    private lateinit var joinError: TextView
    private lateinit var serverInput: EditText
    private lateinit var adminInput: EditText
    private lateinit var advanced: LinearLayout
    private var titleTaps = 0
    private var lastTitleTap = 0L
    private lateinit var codeInput: EditText
    private lateinit var inviteBtn: Button
    private lateinit var pttStatus: TextView
    private lateinit var pttList: LinearLayout
    private lateinit var pttSearch: Button
    private lateinit var pttForget: Button
    private val pttScanner by lazy { PttScanner(this) }
    private val pttFound = LinkedHashMap<String, PttScanner.Found>()
    private lateinit var roomPtt: TextView

    // tela do canal
    private lateinit var roomScreen: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var speaker: TextView
    private lateinit var talkButton: Button
    private lateinit var modePtt: Button
    private lateinit var modeVox: Button
    private lateinit var voxBox: LinearLayout
    private lateinit var levelBar: ProgressBar
    private lateinit var sensitivity: SeekBar
    private lateinit var peopleText: TextView
    private var holdToTalk = false
    private var wasInRoom = false
    private lateinit var roomInvite: Button
    private lateinit var priorityBox: CheckBox
    private lateinit var testBtn: Button
    private lateinit var testStatus: TextView
    private lateinit var testLevel: ProgressBar
    private var audioTest: AudioTest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 35) { // no Android 15+ as barras são transparentes e mostram o fundo do app
            @Suppress("DEPRECATION")
            window.statusBarColor = bg
            @Suppress("DEPRECATION")
            window.navigationBarColor = bg
        }
        discovery = Discovery(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        joinScreen = buildJoinScreen()
        roomScreen = buildRoomScreen()
        root.addView(joinScreen, ViewGroup.LayoutParams(-1, -1))
        root.addView(roomScreen, ViewGroup.LayoutParams(-1, -1))
        // Android 15+ desenha o app por baixo da barra de status e da de navegação:
        // afasta o conteúdo delas (e do teclado), para nada ficar escondido.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)
        askPermissions()
        handleInvite(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
        if (IntercomService.instance?.running != true) startSearching()
    }

    override fun onPause() {
        super.onPause()
        audioTest?.cancel()
        pttScanner.stop()
        ui.removeCallbacks(refresh)
        discovery.stopSearching()
    }

    // ======================================================================
    // Tela de entrada
    // ======================================================================

    private fun buildJoinScreen(): View {
        val col = column(24, 40)
        // 7 toques no título abrem (ou fecham) as opções avançadas, como o "modo desenvolvedor" do Android.
        col.addView(label("Comunicador", 30f, fg, bold = true).apply { setOnClickListener { onTitleTap() } })
        col.addView(label("Todos no mesmo Wi-Fi, ou no hotspot do celular que for a central.", 15f, muted).withTop(6))

        col.addView(label("Seu nome ou função", 15f, fg, bold = true).withTop(28))
        nameInput = input("ex.: Fernando – direção").apply { setText(prefs.getString("nome", "")) }
        col.addView(nameInput.withTop(8))

        btMic = CheckBox(this).apply {
            this.text = "Usar o microfone do fone Bluetooth"
            setTextColor(fg)
            isChecked = prefs.getBoolean("btMic", false)
        }
        col.addView(btMic.withTop(12))
        col.addView(label("Deixe desmarcado com fone com fio. Marque se o seu fone é Bluetooth e você quer falar por ele.", 13f, muted))
        priorityBox = CheckBox(this).apply {
            this.text = "Tenho prioridade (direção ou produção)"
            setTextColor(fg)
            isChecked = prefs.getBoolean("prioridade", false)
        }
        col.addView(priorityBox.withTop(8))
        col.addView(label("Quando você fala, as outras vozes abaixam para todo mundo ouvir você.", 13f, muted))

        // ---------- Teste de áudio ----------
        testBtn = secondary("Testar áudio (3 segundos)") { runAudioTest() }
        col.addView(testBtn.withTop(16))
        testLevel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        col.addView(testLevel.withTop(8))
        testStatus = label("Ponha o fone e confira se você se ouve antes da diária.", 13f, muted)
        col.addView(testStatus.withTop(4))

        joinError = label("", 14f, tally)
        col.addView(joinError.withTop(12))

        // ---------- Pela internet ----------
        col.addView(label("Pela internet", 20f, fg, bold = true).withTop(20))
        col.addView(label("Cada um no próprio 4G ou Wi-Fi. Quem recebeu um convite já tem tudo preenchido.", 13f, muted).withTop(4))
        col.addView(label("Código do canal", 15f, fg, bold = true).withTop(14))
        codeInput = input("ex.: K7QX4M").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(prefs.getString("code", ""))
        }
        col.addView(codeInput.withTop(8))
        col.addView(primary("Entrar no canal") { joinInternet() }.withTop(10))
        inviteBtn = secondary("Criar canal novo e convidar a equipe") { createAndInvite() }
        col.addView(inviteBtn.withTop(8))

        // ---------- Opções avançadas (escondidas) ----------
        advanced = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (prefs.getBoolean("avancado", false)) View.VISIBLE else View.GONE
        }
        advanced.addView(label("Opções avançadas", 20f, fg, bold = true).withTop(28))
        advanced.addView(label("Para a produção. Toque 7 vezes em \"Comunicador\" para esconder de novo.", 13f, muted).withTop(4))
        advanced.addView(label("Servidor", 15f, fg, bold = true).withTop(14))
        serverInput = input("wss://… ou IP do servidor").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("server", null) ?: Protocol.DEFAULT_SERVER)
            textSize = 14f
        }
        advanced.addView(serverInput.withTop(8))
        advanced.addView(label("Cloudflare: wss://nome.sua-conta.workers.dev   ·   Cloudflare com WebRTC: webrtc://nome.sua-conta.workers.dev   ·   Servidor próprio ou Oracle: o IP dele.", 12f, muted).withTop(4))
        advanced.addView(secondary("Voltar ao servidor padrão") { serverInput.setText(Protocol.DEFAULT_SERVER) }.withTop(8))
        advanced.addView(label("Código de administrador", 15f, fg, bold = true).withTop(14))
        adminInput = input("só para quem cria canais").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = Typeface.DEFAULT // campo de senha troca a fonte por uma de máquina de escrever
            setText(prefs.getString("admin", ""))
        }
        advanced.addView(adminInput.withTop(8))
        advanced.addView(label("A central só deixa criar canal novo com este código. Quem entra por convite não precisa dele.", 12f, muted).withTop(4))
        col.addView(advanced)

        // ---------- Sem internet ----------
        col.addView(label("Sem internet: Wi-Fi do set", 20f, fg, bold = true).withTop(32))
        col.addView(primary("Criar canal neste celular") { start(asHost = true, server = "", code = "", key = localKey()) }.withTop(10))
        col.addView(label("Este celular vira a central. Os outros entram nele com a senha que aparece na tela dela.", 13f, muted).withTop(6))

        col.addView(label("Canais encontrados", 15f, fg, bold = true).withTop(28))
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(foundList.withTop(8))
        foundEmpty = label("Procurando na rede…", 14f, muted)
        col.addView(foundEmpty)

        col.addView(label("Ou entre pelo endereço da central", 15f, fg, bold = true).withTop(28))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ipInput = input("ex.: 192.168.0.10").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("ip", ""))
        }
        row.addView(ipInput, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(secondary("Entrar") {
            val ip = ipInput.text.toString().trim()
            if (ip.isEmpty()) joinError.text = "Digite o endereço que aparece na tela da central."
            else {
                prefs.edit().putString("ip", ip).apply()
                askLocalKey { key -> start(asHost = false, server = ip, code = "", key = key) }
            }
        }, LinearLayout.LayoutParams(-2, -1).apply { marginStart = dp(8) })
        col.addView(row.withTop(8))

        col.addView(label("Botão PTT Bluetooth (opcional)", 15f, fg, bold = true).withTop(28))
        col.addView(label("Para segurar o botão e falar, soltar e parar. Funciona com os botões compatíveis com Zello.", 13f, muted).withTop(4))
        pttStatus = label("", 14f, fg)
        col.addView(pttStatus.withTop(8))
        val pttRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pttSearch = secondary("Procurar botão") { searchPtt() }
        pttForget = secondary("Esquecer botão") {
            prefs.edit().remove("pttAddr").remove("pttName").apply()
            renderPttStatus()
        }
        pttRow.addView(pttSearch, LinearLayout.LayoutParams(0, -2, 1f))
        pttRow.addView(pttForget, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(pttRow.withTop(8))
        pttList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pttList.withTop(4))
        renderPttStatus()

        col.addView(secondary("Permitir que funcione com a tela desligada") { askBatteryExemption() }.withTop(32))
        col.addView(label("Faça isso uma vez em cada celular, senão o sistema pode fechar o app para economizar bateria.", 13f, muted).withTop(6))

        return ScrollView(this).apply { addView(col) }
    }

    private fun startSearching() {
        found.clear()
        renderFound()
        discovery.search(
            found = { f -> found[f.name] = f; renderFound() },
            lost = { name -> found.remove(name); renderFound() },
        )
    }

    private fun renderFound() {
        foundList.removeAllViews()
        foundEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        for (f in found.values) {
            foundList.addView(secondary("Entrar no canal de ${f.name}") { askLocalKey { key -> start(asHost = false, server = "udp://${f.host}:${f.port}", code = "", key = key) } }.withTop(6))
        }
    }

    // ======================================================================
    // Internet: entrar, criar canal e convidar
    // ======================================================================

    private fun serverValue(): String = serverInput.text.toString().trim()

    private fun onTitleTap() {
        val now = System.currentTimeMillis()
        titleTaps = if (now - lastTitleTap < 1500) titleTaps + 1 else 1
        lastTitleTap = now
        if (titleTaps < 7) return
        titleTaps = 0
        val show = advanced.visibility != View.VISIBLE
        advanced.visibility = if (show) View.VISIBLE else View.GONE
        prefs.edit().putBoolean("avancado", show).apply()
        Toast.makeText(this, if (show) "Opções avançadas abertas" else "Opções avançadas escondidas", Toast.LENGTH_SHORT).show()
    }

    /** Senha da central no Wi-Fi local: criada uma vez e mostrada na tela do canal. */
    private fun localKey(): String =
        prefs.getString("senhaLocal", null) ?: Seguranca.novaSenhaLocal().also { prefs.edit().putString("senhaLocal", it).apply() }

    /** Pede a senha que aparece na tela da central antes de entrar no Wi-Fi local. */
    private fun askLocalKey(then: (String) -> Unit) {
        val field = input("ex.: K7QX4M").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setText(prefs.getString("senhaLocalDigitada", ""))
        }
        AlertDialog.Builder(this)
            .setTitle("Senha da central")
            .setMessage("Está na tela do celular que criou o canal.")
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("Entrar") { _, _ ->
                val key = field.text.toString().trim().uppercase()
                prefs.edit().putString("senhaLocalDigitada", key).apply()
                then(key)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Senha guardada para este código de canal (vem no convite ou foi criada aqui). */
    private fun keyFor(code: String): String =
        if (code.isNotEmpty() && prefs.getString("keyCode", "") == code) prefs.getString("key", "").orEmpty() else ""

    private fun saveKey(code: String, key: String) {
        prefs.edit().putString("keyCode", code).putString("key", key).apply()
    }

    private fun joinInternet() {
        val server = serverValue()
        val code = codeInput.text.toString().trim().uppercase()
        if (server.isEmpty()) { joinError.text = "Falta o endereço do servidor. Peça o convite para a produção."; return }
        if (code.length < 4) { joinError.text = "Digite o código do canal (está no convite)."; return }
        prefs.edit().putString("server", server).putString("code", code).apply()
        start(asHost = false, server = server, code = code)
    }

    private fun createAndInvite() {
        val server = serverValue()
        if (server.isEmpty()) { joinError.text = "Preencha o endereço do servidor antes de criar o canal."; return }
        if (nameInput.text.toString().isBlank()) { joinError.text = "Digite seu nome primeiro."; return }
        val code = Protocol.newChannelCode()
        saveKey(code, Seguranca.novaSenha()) // senha do canal: vai junto no convite, ninguém digita
        codeInput.setText(code)
        shareInvite(server, code)
        joinInternet()
    }

    /** Link que abre uma página do servidor com o botão "Abrir no Comunicador". */
    private fun inviteLink(server: String, code: String): String {
        val s = server.trim().trimEnd('/')
        val key = keyFor(code)
        val k = if (key.isNotEmpty()) "k=$key" else ""
        fun query(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return if (s.startsWith("webrtc://")) {
            val host = s.substringAfter("://").substringBefore('/')
            "https://$host/c/$code" + query("voz=webrtc", k)
        } else if (s.startsWith("wss://") || s.startsWith("https://") || s.startsWith("ws://")) {
            val host = s.substringAfter("://").substringBefore('/')
            "https://$host/c/$code" + query(k)
        } else {
            val host = s.removePrefix("udp://").substringBeforeLast(':', s.removePrefix("udp://"))
            "http://$host:8080/c/$code" + query(k)
        }
    }

    private fun shareInvite(server: String, code: String) {
        val text = "Entre no canal $code do Comunicador do set: ${inviteLink(server, code)}"
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Convidar a equipe",
        ))
    }

    /** Convite aberto pelo link: comunicador://entrar?s=SERVIDOR&c=CODIGO */
    private fun handleInvite(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "comunicador") return
        val server = uri.getQueryParameter("s").orEmpty()
        val code = uri.getQueryParameter("c").orEmpty().uppercase()
        if (server.isEmpty() || code.isEmpty()) return
        saveKey(code, uri.getQueryParameter("k").orEmpty())
        serverInput.setText(server)
        codeInput.setText(code)
        prefs.edit().putString("server", server).putString("code", code).apply()
        joinError.setTextColor(ok)
        joinError.text = "Convite recebido: canal $code. Digite seu nome e toque em Entrar no canal."
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInvite(intent)
    }

    private fun start(asHost: Boolean, server: String, code: String, key: String = keyFor(code)) {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { joinError.text = "Digite seu nome para a equipe saber quem está falando."; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            joinError.text = "O app precisa do microfone. Permita o acesso e tente de novo."
            askPermissions()
            return
        }
        joinError.text = ""
        joinError.setTextColor(tally)
        prefs.edit().putString("nome", name).putBoolean("btMic", btMic.isChecked)
            .putString("admin", adminInput.text.toString().trim())
            .putBoolean("prioridade", priorityBox.isChecked).apply()
        discovery.stopSearching()
        val i = Intent(this, IntercomService::class.java)
            .setAction(if (asHost) IntercomService.ACTION_HOST else IntercomService.ACTION_JOIN)
            .putExtra(IntercomService.EXTRA_NAME, name)
            .putExtra(IntercomService.EXTRA_SERVER, server)
            .putExtra(IntercomService.EXTRA_CODE, code)
            .putExtra(IntercomService.EXTRA_BT_MIC, btMic.isChecked)
            .putExtra(IntercomService.EXTRA_PRIORITY, priorityBox.isChecked)
            .putExtra(IntercomService.EXTRA_KEY, key)
            .putExtra(IntercomService.EXTRA_ADMIN, adminInput.text.toString().trim())
        // As chaves do canal levam uma fração de segundo para sair da senha: calcula fora da
        // tela (fica guardado) e só então liga o serviço, que já as encontra prontas.
        joinError.setTextColor(muted)
        joinError.text = "Preparando…"
        Thread {
            Seguranca.derivar(key, code.uppercase().ifEmpty { "LOCAL" })
            ui.post {
                if (joinError.text == "Preparando…") joinError.text = ""
                joinError.setTextColor(tally)
                startForegroundService(i)
                ui.postDelayed({ applySavedSettings() }, 300)
            }
        }.start()
    }

    private fun applySavedSettings() {
        val s = IntercomService.instance ?: return
        s.changeMode(if (prefs.getString("modo", "PTT") == "VOX") IntercomService.Mode.VOX else IntercomService.Mode.PTT)
        s.voxThreshold = thresholdFor(prefs.getInt("sens", 50))
        sensitivity.progress = prefs.getInt("sens", 50)
    }

    // ======================================================================
    // Tela do canal
    // ======================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRoomScreen(): View {
        val col = column(20, 16)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusDot = View(this).apply { background = circle(muted) }
        bar.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusText = label("", 14f, muted).apply { setPadding(dp(8), 0, 0, 0) }
        bar.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))
        roomInvite = secondary("Convidar") {
            val s = IntercomService.instance ?: return@secondary
            shareInvite(prefs.getString("server", "") ?: "", s.channelCode)
        }
        bar.addView(roomInvite, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        bar.addView(secondary("Sair") {
            startService(Intent(this, IntercomService::class.java).setAction(IntercomService.ACTION_STOP))
        })
        col.addView(bar)

        speaker = label("Canal livre", 20f, muted, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(14), dp(12), dp(14))
            background = rounded(Color.TRANSPARENT, line, 12)
        }
        col.addView(speaker.withTop(14))

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modePtt = secondary("Botão") { setMode(IntercomService.Mode.PTT) }
        modeVox = secondary("Por voz") { setMode(IntercomService.Mode.VOX) }
        modes.addView(modePtt, LinearLayout.LayoutParams(0, -2, 1f))
        modes.addView(modeVox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        col.addView(modes.withTop(14))

        talkButton = Button(this).apply {
            setTextColor(fg)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            background = circle(panel, line, 6)
        }
        talkButton.setOnTouchListener { _, ev ->
            val s = IntercomService.instance ?: return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (s.mode == IntercomService.Mode.VOX) {
                        s.toggle()
                    } else if (s.pttOn) {
                        s.setPtt(false)           // estava travado pelo fone: toque desliga
                    } else {
                        holdToTalk = true
                        s.setPtt(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (holdToTalk) { holdToTalk = false; s.setPtt(false) }
                }
            }
            true
        }
        val size = (resources.displayMetrics.widthPixels * 0.66).toInt()
        col.addView(talkButton, LinearLayout.LayoutParams(size, size).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(24)
        })

        voxBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        voxBox.addView(label("Sensibilidade da voz", 14f, fg, bold = true))
        voxBox.addView(label("Fale normalmente: a barra deve passar da metade. Se transmitir barulho do set, diminua.", 13f, muted))
        levelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        voxBox.addView(levelBar.withTop(8))
        sensitivity = SeekBar(this).apply {
            max = 100
            progress = prefs.getInt("sens", 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    IntercomService.instance?.voxThreshold = thresholdFor(p)
                    if (fromUser) prefs.edit().putInt("sens", p).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        voxBox.addView(sensitivity.withTop(4))
        col.addView(voxBox.withTop(20))

        col.addView(label("Pode travar o celular. Pelo fone: um toque no botão liga, outro desliga. Também dá para falar pela notificação na tela de bloqueio.", 13f, muted).withTop(16))

        roomPtt = label("", 13f, muted)
        col.addView(roomPtt.withTop(8))

        col.addView(label("No canal", 14f, fg, bold = true).withTop(20))
        peopleText = label("", 15f, fg)
        col.addView(peopleText.withTop(4))

        return ScrollView(this).apply { addView(col) }
    }

    private fun setMode(m: IntercomService.Mode) {
        prefs.edit().putString("modo", m.name).apply()
        IntercomService.instance?.changeMode(m)
    }

    /** Sensibilidade alta = limiar baixo. Escala aproximadamente logarítmica. */
    private fun thresholdFor(sens: Int): Double = 120.0 * Math.pow(10.0, (100 - sens) / 50.0)

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 150)
        }
    }

    private fun render() {
        val s = IntercomService.instance
        val inRoom = s != null && s.running
        joinScreen.visibility = if (inRoom) View.GONE else View.VISIBLE
        roomScreen.visibility = if (inRoom) View.VISIBLE else View.GONE
        if (wasInRoom && !inRoom) startSearching()
        wasInRoom = inRoom
        if (!inRoom || s == null) return

        val connected = s.connectedToHost
        statusDot.background = circle(if (connected && !s.keyRejected) ok else tally)
        statusText.text = when {
            s.keyRejected -> s.hostLabel
            s.isHost -> "${s.hostLabel} · ${s.codecLabel}"
            connected -> "${s.hostLabel} · ${s.routeLabel} · ${s.codecLabel}"
            s.channelCode.isNotEmpty() -> "Reconectando ao canal ${s.channelCode}…"
            else -> "Sem contato com a central…"
        }
        roomInvite.visibility = if (s.channelCode.isNotEmpty()) View.VISIBLE else View.GONE

        val others = s.people.filter { it.talking && it.name != s.myName }.map { it.name }
        when {
            s.transmitting -> { speaker.text = "Você está no ar"; speaker.setTextColor(Color.WHITE); speaker.background = rounded(tally, tally, 12) }
            others.isNotEmpty() -> { speaker.text = others.joinToString(", ") + if (others.size == 1) " está falando" else " estão falando"; speaker.setTextColor(Color.parseColor("#231A00")); speaker.background = rounded(cue, cue, 12) }
            else -> { speaker.text = "Canal livre"; speaker.setTextColor(muted); speaker.background = rounded(Color.TRANSPARENT, line, 12) }
        }

        val vox = s.mode == IntercomService.Mode.VOX
        modePtt.background = rounded(if (!vox) line else Color.TRANSPARENT, line, 10)
        modeVox.background = rounded(if (vox) line else Color.TRANSPARENT, line, 10)
        voxBox.visibility = if (vox) View.VISIBLE else View.GONE

        talkButton.text = when {
            vox && s.voxMuted -> "Silenciado\ntoque para abrir"
            vox && s.transmitting -> "No ar"
            vox -> "Microfone aberto\ntoque para silenciar"
            s.transmitting -> "No ar"
            else -> "Segure\npara falar"
        }
        talkButton.background = when {
            s.transmitting -> circle(tally, Color.parseColor("#FF8A92"), 6)
            vox && s.voxMuted -> circle(bg, line, 6)
            else -> circle(panel, line, 6)
        }

        if (vox) {
            // Barra em escala log para combinar com o controle de sensibilidade
            val lvl = s.micLevel.coerceAtLeast(1.0)
            levelBar.progress = ((Math.log10(lvl / 120.0) / 2.0) * 100).toInt().coerceIn(0, 100)
        }

        roomPtt.text = when (s.pttButton?.state) {
            PttButton.State.READY -> if (s.buttonHeld) "Botão PTT: apertado" else "Botão PTT: pronto"
            PttButton.State.CONNECTING -> "Botão PTT: aperte uma vez para acordar e conectar"
            else -> if (prefs.getString("pttAddr", null) != null) "Botão PTT: sem conexão. Saia e entre de novo." else ""
        }
        // No modo WebRTC, mostra também a situação da voz (conectando, conectada, fora do ar).
        val voice = s.voiceStatus
        if (voice.isNotEmpty()) roomPtt.text = if (roomPtt.text.isEmpty()) voice else "$voice\n${roomPtt.text}"
        roomPtt.visibility = if (roomPtt.text.isEmpty()) View.GONE else View.VISIBLE

        peopleText.text = s.people.joinToString("\n") { p ->
            (if (p.talking) "● " else "   ") + p.name + if (p.name == s.myName) " (você)" else ""
        }.ifEmpty { "Só você por enquanto." }
    }

    // ======================================================================
    // Teste de áudio
    // ======================================================================

    private fun runAudioTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            testStatus.setTextColor(tally)
            testStatus.text = "O app precisa do microfone. Permita o acesso e tente de novo."
            askPermissions()
            return
        }
        if (IntercomService.instance?.running == true) {
            testStatus.text = "Saia do canal para testar o áudio."
            return
        }
        testBtn.isEnabled = false
        testLevel.visibility = View.VISIBLE
        testStatus.setTextColor(fg)
        audioTest = AudioTest(
            context = this,
            useBtMic = btMic.isChecked,
            onLevel = { lvl ->
                // mesma escala da barra de sensibilidade do modo por voz
                testLevel.progress = ((Math.log10(lvl.coerceAtLeast(1.0) / 120.0) / 2.0) * 100).toInt().coerceIn(0, 100)
            },
            onStage = { stage, msg ->
                testStatus.text = msg
                when (stage) {
                    AudioTest.Stage.DONE -> { testStatus.setTextColor(ok); finishAudioTest() }
                    AudioTest.Stage.ERROR -> { testStatus.setTextColor(tally); finishAudioTest() }
                    else -> testStatus.setTextColor(fg)
                }
            },
        ).also { it.start() }
    }

    private fun finishAudioTest() {
        testBtn.isEnabled = true
        testLevel.visibility = View.GONE
        audioTest = null
    }

    // ======================================================================
    // Botão PTT
    // ======================================================================

    private fun renderPttStatus() {
        val name = prefs.getString("pttName", null)
        pttStatus.text = if (name != null) "Botão escolhido: $name" else "Nenhum botão escolhido."
        pttForget.visibility = if (name != null) View.VISIBLE else View.GONE
    }

    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun searchPtt() {
        val missing = bluetoothPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 2)
            pttStatus.text = "Permita o acesso a dispositivos próximos e toque em Procurar de novo."
            return
        }
        if (!pttScanner.isBluetoothOn()) {
            pttStatus.text = "Ligue o Bluetooth do celular e toque em Procurar de novo."
            return
        }
        pttFound.clear()
        pttList.removeAllViews()
        pttSearch.isEnabled = false
        pttStatus.text = "Procurando… Segure o botão PTT apertado por alguns segundos para ele aparecer."
        pttScanner.start(
            onFound = { f ->
                if (pttFound.put(f.address, f) == null) renderPttList()
            },
            onDone = {
                pttSearch.isEnabled = true
                if (pttFound.isEmpty()) pttStatus.text = "Nenhum botão encontrado. Segure o botão apertado e procure de novo." else renderPttStatus()
            },
        )
    }

    private fun renderPttList() {
        pttList.removeAllViews()
        // Os que parecem botão PTT aparecem primeiro.
        for (f in pttFound.values.sortedByDescending { it.likelyPtt }) {
            val label = (if (f.likelyPtt) "Usar: " else "Usar (talvez não seja um botão PTT): ") + f.name
            pttList.addView(secondary(label) {
                pttScanner.stop()
                pttSearch.isEnabled = true
                prefs.edit().putString("pttAddr", f.address).putString("pttName", f.name).apply()
                pttList.removeAllViews()
                renderPttStatus()
            }.withTop(6))
        }
    }

    // ======================================================================
    // Permissões
    // ======================================================================

    private fun askPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            joinError.setTextColor(ok)
            joinError.text = "Pronto: o app já pode funcionar com a tela desligada."
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ======================================================================
    // Pequenos ajudantes de interface
    // ======================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column(h: Int, v: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(h), dp(v), dp(h), dp(v))
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun input(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(muted)
        setTextColor(fg)
        textSize = 17f
        isSingleLine = true
        background = rounded(panel, line, 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun primary(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(bg)
        background = rounded(fg, fg, 10)
        setOnClickListener { onClick() }
    }

    private fun secondary(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 15f
        setTextColor(fg)
        background = rounded(Color.TRANSPARENT, line, 10)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun circle(fill: Int, stroke: Int = fill, strokeDp: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun <T : View> T.withTop(topDp: Int): T {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topDp) }
        return this
    }
}
