package br.comunicador.set

import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocolo do comunicador (versão 2). O mesmo formato vale para os três caminhos:
 * central no celular (UDP, Wi-Fi local), servidor próprio (UDP, internet) e Cloudflare (WebSocket).
 *
 *  HELLO  participante -> central   [1][tamNome][nome][tamCódigo][código][tamSenha][senha][opções]
 *         opções: bit 1 = tem prioridade (direção/produção)
 *  AUDIO  nos dois sentidos         [2][id u16][seq u16][codec u8][dados]
 *  ROSTER central -> participante   [3][seuId u16][qtd]{[id u16][estado u8][tamNome][nome]}
 *         estado: bit 0 = falando, bit 1 = tem prioridade
 *  BYE    participante -> central   [4]
 *  TALK   participante -> central   [5][1 = começou a falar, 0 = parou]
 *  RTC_PUB  participante -> central (só WebRTC)  [6][tam][sessão no SFU][tam][nome da trilha]
 *  RTC_LIST central -> participantes (só WebRTC) [7][qtd]{[id u16][tam][sessão][tam][trilha]}
 *  ERROR  central -> participante   [8][motivo]  (1 = senha do canal errada)
 *  RTC_UNPUB participante -> central (só WebRTC) [9]  ("minha voz agora vem pelo WebSocket")
 *
 * Codecs do áudio: 0 = PCM, 1 = ADPCM, 2 = Opus.
 *
 * Quem manda áudio usa id 0; a central troca pelo id de quem falou antes de repassar.
 */
object Protocol {
    const val PORT = 50505
    const val SAMPLE_RATE = 16000
    const val FRAME = 320 // 20 ms
    const val SERVICE_TYPE = "_comunicador._udp."
    const val HOST_ID = 1

    /**
     * Servidor que já vem preenchido no app. Coloque aqui o endereço do seu servidor
     * (ex.: "wss://comunicador.sua-conta.workers.dev") e a equipe nem precisa ver esse campo.
     */
    const val DEFAULT_SERVER = ""

    const val HELLO: Byte = 1
    const val AUDIO: Byte = 2
    const val ROSTER: Byte = 3
    const val BYE: Byte = 4
    const val TALK: Byte = 5
    const val RTC_PUB: Byte = 6
    const val RTC_LIST: Byte = 7
    const val ERROR: Byte = 8
    const val RTC_UNPUB: Byte = 9
    const val ERR_WRONG_KEY = 1

    const val CODEC_PCM: Byte = 0
    const val CODEC_ADPCM: Byte = 1
    const val CODEC_OPUS: Byte = 2
    const val AUDIO_HEADER = 6

    data class Person(val id: Int, val name: String, val talking: Boolean, val priority: Boolean = false)

    data class Hello(val name: String, val code: String, val key: String, val priority: Boolean)

    /** Onde ouvir a voz de alguém no modo WebRTC: sessão e trilha no Cloudflare Realtime. */
    data class RtcPub(val id: Int, val sessionId: String, val trackName: String)

    private fun utf8(s: String, max: Int): ByteArray {
        var t = s
        while (t.toByteArray(Charsets.UTF_8).size > max) t = t.dropLast(1) // corta sem quebrar acentos
        return t.toByteArray(Charsets.UTF_8)
    }

    fun hello(name: String, code: String = "", key: String = "", priority: Boolean = false): ByteArray {
        val n = utf8(name, 60)
        val c = utf8(code, 40)
        val k = utf8(key, 40)
        return byteArrayOf(HELLO, n.size.toByte()) + n + byteArrayOf(c.size.toByte()) + c +
            byteArrayOf(k.size.toByte()) + k + byteArrayOf(if (priority) 2 else 0)
    }

    fun parseHello(data: ByteArray, length: Int): Hello {
        val fields = ArrayList<String>()
        var i = 1
        repeat(3) {
            if (i >= length) return@repeat
            val n = minOf(data[i].toInt() and 0xff, length - i - 1)
            fields.add(String(data, i + 1, n, Charsets.UTF_8).trim())
            i += 1 + n
        }
        val flags = if (i < length) data[i].toInt() and 0xff else 0
        return Hello(
            name = fields.getOrNull(0).orEmpty().ifEmpty { "Sem nome" },
            code = fields.getOrNull(1).orEmpty(),
            key = fields.getOrNull(2).orEmpty(),
            priority = flags and 2 != 0,
        )
    }

    fun rtcUnpub(): ByteArray = byteArrayOf(RTC_UNPUB)

    fun error(reason: Int): ByteArray = byteArrayOf(ERROR, reason.toByte())

    /** Pacote de áudio já comprimido por fora (Opus). */
    fun audioRaw(id: Int, seq: Int, codec: Byte, payload: ByteArray): ByteArray {
        val out = ByteArray(AUDIO_HEADER + payload.size)
        out[0] = AUDIO
        out[1] = (id and 0xff).toByte(); out[2] = ((id shr 8) and 0xff).toByte()
        out[3] = (seq and 0xff).toByte(); out[4] = ((seq shr 8) and 0xff).toByte()
        out[5] = codec
        System.arraycopy(payload, 0, out, AUDIO_HEADER, payload.size)
        return out
    }

    fun audioCodec(data: ByteArray, length: Int): Byte = if (length > AUDIO_HEADER) data[5] else -1

    fun bye(): ByteArray = byteArrayOf(BYE)

    fun talk(on: Boolean): ByteArray = byteArrayOf(TALK, if (on) 1 else 0)

    fun rtcPub(sessionId: String, trackName: String): ByteArray {
        val s = utf8(sessionId, 200)
        val t = utf8(trackName, 60)
        return byteArrayOf(RTC_PUB, s.size.toByte()) + s + byteArrayOf(t.size.toByte()) + t
    }

    fun parseRtcList(data: ByteArray, length: Int): List<RtcPub> {
        val out = ArrayList<RtcPub>()
        if (length < 2) return out
        val count = data[1].toInt() and 0xff
        var i = 2
        repeat(count) {
            if (i + 3 > length) return out
            val id = (data[i].toInt() and 0xff) or ((data[i + 1].toInt() and 0xff) shl 8)
            val sl = data[i + 2].toInt() and 0xff
            i += 3
            if (i + sl + 1 > length) return out
            val sid = String(data, i, sl, Charsets.UTF_8)
            i += sl
            val tl = data[i].toInt() and 0xff
            i += 1
            if (i + tl > length) return out
            val track = String(data, i, tl, Charsets.UTF_8)
            i += tl
            out.add(RtcPub(id, sid, track))
        }
        return out
    }

    /** Monta um pacote de áudio comprimido (ADPCM). */
    fun audio(id: Int, seq: Int, pcm: ShortArray, encoder: ImaAdpcm): ByteArray {
        val out = ByteArray(AUDIO_HEADER + ImaAdpcm.encodedSize(pcm.size))
        out[0] = AUDIO
        out[1] = (id and 0xff).toByte(); out[2] = ((id shr 8) and 0xff).toByte()
        out[3] = (seq and 0xff).toByte(); out[4] = ((seq shr 8) and 0xff).toByte()
        out[5] = CODEC_ADPCM
        encoder.encode(pcm, out, AUDIO_HEADER)
        return out
    }

    /** Troca o id dentro de um pacote de áudio já pronto (usado pela central ao repassar). */
    fun withId(packet: ByteArray, length: Int, id: Int): ByteArray {
        val out = packet.copyOf(length)
        out[1] = (id and 0xff).toByte()
        out[2] = ((id shr 8) and 0xff).toByte()
        return out
    }

    fun audioId(data: ByteArray): Int = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)

    /** Devolve o áudio do pacote já descomprimido, ou null se o formato for desconhecido. */
    fun audioPcm(data: ByteArray, length: Int): ShortArray? {
        if (length <= AUDIO_HEADER) return null
        return when (data[5]) {
            CODEC_ADPCM -> ImaAdpcm.decode(data, AUDIO_HEADER, length - AUDIO_HEADER)
            CODEC_PCM -> {
                val n = (length - AUDIO_HEADER) / 2
                val out = ShortArray(n)
                val bb = ByteBuffer.wrap(data, AUDIO_HEADER, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[i] = bb.short
                out
            }
            else -> null
        }
    }

    fun helloName(data: ByteArray, length: Int): String {
        if (length < 2) return "Sem nome"
        val n = minOf(data[1].toInt() and 0xff, length - 2)
        return String(data, 2, n, Charsets.UTF_8).trim().ifEmpty { "Sem nome" }
    }

    fun helloCode(data: ByteArray, length: Int): String {
        if (length < 2) return ""
        val at = 2 + (data[1].toInt() and 0xff)
        if (at >= length) return ""
        val n = minOf(data[at].toInt() and 0xff, length - at - 1)
        return String(data, at + 1, n, Charsets.UTF_8).trim()
    }

    fun roster(yourId: Int, people: List<Person>): ByteArray {
        val bb = ByteBuffer.allocate(1400).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(ROSTER).putShort(yourId.toShort())
        val list = people.take(60)
        bb.put(list.size.toByte())
        for (p in list) {
            val n = utf8(p.name, 20)
            if (bb.remaining() < 4 + n.size) break
            bb.putShort(p.id.toShort()).put(((if (p.talking) 1 else 0) or (if (p.priority) 2 else 0)).toByte()).put(n.size.toByte()).put(n)
        }
        return bb.array().copyOf(bb.position())
    }

    /** Devolve (meuId, pessoas). */
    fun parseRoster(data: ByteArray, length: Int): Pair<Int, List<Person>> {
        val bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        bb.get()
        val me = bb.short.toInt() and 0xffff
        val count = bb.get().toInt() and 0xff
        val people = ArrayList<Person>(count)
        repeat(count) {
            if (bb.remaining() < 4) return me to people
            val id = bb.short.toInt() and 0xffff
            val state = bb.get().toInt() and 0xff
            val n = bb.get().toInt() and 0xff
            if (bb.remaining() < n) return me to people
            val bytes = ByteArray(n)
            bb.get(bytes)
            people.add(Person(id, String(bytes, Charsets.UTF_8), state and 1 != 0, state and 2 != 0))
        }
        return me to people
    }

    fun rms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s
        return Math.sqrt(sum / pcm.size)
    }

    /** Endereços IPv4 deste celular na rede local (Wi-Fi ou hotspot). */
    fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .filter { nic -> listOf("rmnet", "ccmni", "dummy", "tun", "p2p").none { nic.name.startsWith(it) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    /** Código de canal fácil de ditar: sem letras parecidas (0/O, 1/I). */
    fun newChannelCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = java.security.SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
