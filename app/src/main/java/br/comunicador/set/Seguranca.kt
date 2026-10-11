package br.comunicador.set

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Senha do canal, criptografia da voz e código de administrador.
 *
 * A senha do canal (vem no convite) nunca sai do celular. Dela saem duas coisas:
 *  - a "prova de entrada", que vai no HELLO e no cabeçalho X-Chave: a central compara a
 *    prova de quem chega com a de quem criou o canal, sem conhecer a senha;
 *  - a chave da voz (AES-256-GCM), que cifra o áudio de ponta a ponta: a central repassa
 *    os pacotes sem conseguir ouvir. Vale no Wi-Fi local, no servidor próprio e na reserva
 *    pelo WebSocket. No WebRTC a voz já vai cifrada pelo próprio WebRTC.
 *
 * Pacote de áudio cifrado: [2][id u16][seq u16][codec | 0x80][nonce 12][áudio cifrado + etiqueta 16]
 * O id fica de fora da cifra porque a central troca o id ao repassar.
 */
object Seguranca {
    const val CIFRADO = 0x80
    private const val NONCE = 12
    private const val ETIQUETA = 128 // bits
    private const val ITERACOES = 100_000
    private val random = SecureRandom()

    class Chaves(val entrada: String, val voz: ByteArray)

    private val cache = HashMap<String, Chaves>()

    /**
     * Deriva as chaves do canal. Sem senha, não há chaves (canal aberto, como antes).
     * Leva uma fração de segundo de propósito: chame fora da tela (MainActivity faz isso antes
     * de ligar o serviço) e o resultado fica guardado.
     */
    fun derivar(senha: String, codigo: String): Chaves? {
        if (senha.isEmpty()) return null
        val id = "$senha\u0000${codigo.uppercase()}"
        synchronized(cache) { cache[id]?.let { return it } }
        // PBKDF2 deixa caro adivinhar a senha a partir de um pacote capturado.
        val mestra = pbkdf2(senha.toByteArray(), "comunicador/canal/${codigo.uppercase()}".toByteArray(), ITERACOES)
        val c = Chaves(
            entrada = hex(hmac(mestra, "entrada")).take(32),
            voz = hmac(mestra, "voz"),
        )
        synchronized(cache) { cache[id] = c }
        return c
    }

    /**
     * PBKDF2-HMAC-SHA256 com 32 bytes de saída (um bloco). Feito aqui, com o HMAC nativo do
     * sistema, porque o PBKDF2 que vem no Android é escrito em Java e chega a levar segundos.
     */
    private fun pbkdf2(senha: ByteArray, sal: ByteArray, iteracoes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(senha, "HmacSHA256"))
        var u = mac.doFinal(sal + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        repeat(iteracoes - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return t
    }

    /** Prova de que quem cria o canal tem o código de administrador da central. */
    fun provaAdmin(admin: String, codigo: String): String =
        if (admin.isEmpty()) "" else hex(hmac(admin.toByteArray(), "comunicador/admin/${codigo.uppercase()}")).take(32)

    /** Senha nova para canal pela internet: vai escondida no convite, ninguém digita. */
    fun novaSenha(tamanho: Int = 12): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        return (1..tamanho).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    /** Senha curta do canal no Wi-Fi local: aparece na tela da central e é digitada por quem entra. */
    fun novaSenhaLocal(): String = Protocol.newChannelCode()

    fun cifrado(data: ByteArray, len: Int): Boolean =
        len > Protocol.AUDIO_HEADER && (data[5].toInt() and CIFRADO) != 0

    /** Cifra um pacote de áudio pronto. */
    fun cifrar(packet: ByteArray, voz: ByteArray): ByteArray {
        val h = Protocol.AUDIO_HEADER
        val nonce = ByteArray(NONCE).also { random.nextBytes(it) }
        val codec = (packet[5].toInt() or CIFRADO).toByte()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(voz, "AES"), GCMParameterSpec(ETIQUETA, nonce))
        c.updateAAD(byteArrayOf(codec, packet[3], packet[4]))
        val corpo = c.doFinal(packet, h, packet.size - h)
        val out = ByteArray(h + NONCE + corpo.size)
        System.arraycopy(packet, 0, out, 0, h)
        out[5] = codec
        System.arraycopy(nonce, 0, out, h, NONCE)
        System.arraycopy(corpo, 0, out, h + NONCE, corpo.size)
        return out
    }

    /** Decifra um pacote de áudio. Devolve o pacote em claro, ou null se a chave não bate. */
    fun decifrar(data: ByteArray, len: Int, voz: ByteArray): ByteArray? {
        val h = Protocol.AUDIO_HEADER
        if (!cifrado(data, len) || len < h + NONCE + ETIQUETA / 8) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(voz, "AES"), GCMParameterSpec(ETIQUETA, data, h, NONCE))
            c.updateAAD(byteArrayOf(data[5], data[3], data[4]))
            val audio = c.doFinal(data, h + NONCE, len - h - NONCE)
            val out = ByteArray(h + audio.size)
            System.arraycopy(data, 0, out, 0, h)
            out[5] = (data[5].toInt() and CIFRADO.inv()).toByte()
            System.arraycopy(audio, 0, out, h, audio.size)
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun hmac(chave: ByteArray, texto: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(chave, "HmacSHA256"))
        return mac.doFinal(texto.toByteArray())
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
