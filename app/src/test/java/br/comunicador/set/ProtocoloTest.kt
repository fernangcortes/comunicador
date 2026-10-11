package br.comunicador.set

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes que rodam no computador (e no GitHub), sem celular. */
class ProtocoloTest {

    @Test
    fun helloIdaEVolta() {
        val p = Protocol.hello("Ana – câmera", "ABCD12", "prova", priority = true, admin = "adm")
        val h = Protocol.parseHello(p, p.size)
        assertEquals("Ana – câmera", h.name)
        assertEquals("ABCD12", h.code)
        assertEquals("prova", h.key)
        assertTrue(h.priority)
    }

    @Test
    fun helloSemAdminContinuaIgual() {
        val p = Protocol.hello("Rui", "ABCD12", "k", priority = false)
        assertEquals(1 + 1 + 3 + 1 + 6 + 1 + 1 + 1, p.size)
        assertFalse(Protocol.parseHello(p, p.size).priority)
    }

    @Test
    fun nomeLongoCortaSemQuebrarAcento() {
        val p = Protocol.hello("ç".repeat(100))
        val h = Protocol.parseHello(p, p.size)
        assertEquals(30, h.name.length) // 60 bytes / 2 bytes por "ç"
    }

    @Test
    fun listaDePessoasIdaEVolta() {
        val pessoas = listOf(
            Protocol.Person(1, "Direção", talking = true, priority = true),
            Protocol.Person(7, "Som direto", talking = false),
        )
        val pkt = Protocol.roster(7, pessoas)
        val (eu, lista) = Protocol.parseRoster(pkt, pkt.size)
        assertEquals(7, eu)
        assertEquals(pessoas, lista)
    }

    @Test
    fun listaWebRtc() {
        val sid = "a".repeat(32)
        val pkt = byteArrayOf(Protocol.RTC_LIST, 1, 5, 0, sid.length.toByte()) + sid.toByteArray() + byteArrayOf(3) + "voz".toByteArray()
        assertEquals(listOf(Protocol.RtcPub(5, sid, "voz")), Protocol.parseRtcList(pkt, pkt.size))
    }

    @Test
    fun adpcmPreservaAVoz() {
        val pcm = ShortArray(Protocol.FRAME) { (8000 * Math.sin(2 * Math.PI * 440 * it / Protocol.SAMPLE_RATE)).toInt().toShort() }
        val pkt = Protocol.audio(0, 9, pcm, ImaAdpcm())
        assertTrue("ADPCM deve comprimir", pkt.size < Protocol.AUDIO_HEADER + pcm.size)
        val volta = Protocol.audioPcm(pkt, pkt.size)!!
        assertEquals(pcm.size, volta.size)
        // Depois de acomodar o passo inicial, o erro fica pequeno perto do volume do sinal.
        val erro = (64 until pcm.size).maxOf { Math.abs(pcm[it] - volta[it]) }
        assertTrue("erro do ADPCM alto demais: $erro", erro < 1500)
    }

    @Test
    fun trocaDeIdPelaCentral() {
        val pkt = Protocol.audioRaw(0, 300, Protocol.CODEC_OPUS, byteArrayOf(1, 2, 3))
        val fwd = Protocol.withId(pkt, pkt.size, 4242)
        assertEquals(4242, Protocol.audioId(fwd))
        assertEquals(Protocol.CODEC_OPUS, Protocol.audioCodec(fwd, fwd.size))
    }

    // ---------------- Segurança ----------------

    @Test
    fun chavesBatemComOsServidores() {
        // Mesmos valores calculados em Python (hashlib) e Node (crypto): o app e as centrais concordam.
        val c = Seguranca.derivar("senhaDoCanal", "abcd12")!!
        assertEquals("88d4311dcd78b9103b48957400403598", c.entrada)
        assertEquals("1f72ef45d0412d6927bc7c08d91908e0cb7c72e61f17441ee4be269025ec04cd", c.voz.joinToString("") { "%02x".format(it) })
        assertEquals("8f6351dc2db0eca23dde4580c55fb39d", Seguranca.provaAdmin("segredo-admin", "ABCD12"))
    }

    @Test
    fun semSenhaNaoHaChaves() {
        assertNull(Seguranca.derivar("", "ABCD12"))
        assertEquals("", Seguranca.provaAdmin("", "ABCD12"))
    }

    @Test
    fun vozCifradaIdaEVolta() {
        val voz = Seguranca.derivar("s3nha", "ABCD12")!!.voz
        val claro = Protocol.audioRaw(0, 77, Protocol.CODEC_OPUS, ByteArray(40) { it.toByte() })
        val cifrado = Seguranca.cifrar(claro, voz)
        assertTrue(Seguranca.cifrado(cifrado, cifrado.size))
        assertEquals(claro.size + 28, cifrado.size)
        // A central troca o id ao repassar; a voz continua decifrável.
        val repassado = Protocol.withId(cifrado, cifrado.size, 9)
        val volta = Seguranca.decifrar(repassado, repassado.size, voz)!!
        assertEquals(9, Protocol.audioId(volta))
        assertArrayEquals(claro.copyOfRange(3, claro.size), volta.copyOfRange(3, volta.size))
    }

    @Test
    fun chaveErradaOuPacoteAlteradoNaoToca() {
        val voz = Seguranca.derivar("certa", "ABCD12")!!.voz
        val outra = Seguranca.derivar("errada", "ABCD12")!!.voz
        val cifrado = Seguranca.cifrar(Protocol.audioRaw(0, 1, Protocol.CODEC_ADPCM, ByteArray(20)), voz)
        assertNull(Seguranca.decifrar(cifrado, cifrado.size, outra))
        val alterado = cifrado.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(Seguranca.decifrar(alterado, alterado.size, voz))
        val outroSeq = cifrado.copyOf().also { it[3] = 2 } // o número de sequência é protegido
        assertNull(Seguranca.decifrar(outroSeq, outroSeq.size, voz))
        assertNotNull(Seguranca.decifrar(cifrado, cifrado.size, voz))
    }

    @Test
    fun cadaPacoteTemNonceDiferente() {
        val voz = Seguranca.derivar("x", "LOCAL")!!.voz
        val claro = Protocol.audioRaw(0, 1, Protocol.CODEC_OPUS, ByteArray(10))
        assertFalse(Seguranca.cifrar(claro, voz).contentEquals(Seguranca.cifrar(claro, voz)))
    }

    @Test
    fun senhasNovas() {
        val s = Seguranca.novaSenha()
        assertEquals(12, s.length)
        assertTrue(s.none { it in "0O1lI" })
        assertEquals(6, Seguranca.novaSenhaLocal().length)
    }
}
