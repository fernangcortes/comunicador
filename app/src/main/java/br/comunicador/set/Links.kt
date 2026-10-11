package br.comunicador.set

import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Ligação de um participante com a central. Duas implementações:
 *  - [UdpLink]: central no celular (Wi-Fi local) ou servidor próprio na internet (VPS, Oracle).
 *  - [WsLink]: Cloudflare (Worker + Durable Object), por WebSocket seguro.
 */
interface Link {
    /** true quando a ligação precisa reenviar o HELLO de tempos em tempos (UDP). */
    val helloKeepalive: Boolean
    val connected: Boolean
    fun start()
    fun send(data: ByteArray)
    fun keepalive()
    fun close()

    companion object {
        /**
         * Cria a ligação a partir do endereço do servidor:
         *   wss://nome.conta.workers.dev   -> Cloudflare
         *   udp://203.0.113.10:50505       -> servidor próprio
         *   203.0.113.10  ou  192.168.0.5  -> UDP na porta padrão
         */
        fun create(
            server: String,
            code: String,
            onPacket: (ByteArray, Int) -> Unit,
            onConnected: () -> Unit,
        ): Link {
            val s = server.trim()
            return when {
                s.startsWith("wss://") || s.startsWith("https://") || s.startsWith("ws://") -> {
                    val secure = !s.startsWith("ws://")
                    val rest = s.substringAfter("://").trimEnd('/')
                    val hostPort = rest.substringBefore('/')
                    val host = hostPort.substringBefore(':')
                    val port = hostPort.substringAfter(':', "").toIntOrNull() ?: if (secure) 443 else 80
                    WsLink(secure, host, port, "/ws/" + code, onPacket, onConnected)
                }
                else -> {
                    val hostPort = s.removePrefix("udp://").trimEnd('/')
                    val host = hostPort.substringBeforeLast(':', hostPort)
                    val port = if (hostPort.contains(':')) hostPort.substringAfterLast(':').toIntOrNull() ?: Protocol.PORT else Protocol.PORT
                    UdpLink(host, port, onPacket, onConnected)
                }
            }
        }
    }
}

class UdpLink(
    private val host: String,
    private val port: Int,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onConnected: () -> Unit,
) : Link {
    override val helloKeepalive = true
    @Volatile private var running = false
    @Volatile private var lastReceived = 0L
    @Volatile private var target: InetSocketAddress? = null
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    override val connected: Boolean
        get() = System.currentTimeMillis() - lastReceived < 6000

    override fun start() {
        running = true
        socket = DatagramSocket().apply { soTimeout = 1000 }
        thread = Thread({ loop() }, "udp-link").also { it.start() }
    }

    private fun loop() {
        val buf = ByteArray(2048)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            // Resolve o endereço (pode ser um nome) e repete se a rede ainda não estiver pronta.
            if (target == null) {
                try {
                    target = InetSocketAddress(InetAddress.getByName(host), port)
                    onConnected()
                } catch (_: Exception) {
                    sleep(2000); continue
                }
            }
            try {
                packet.length = buf.size
                socket?.receive(packet) ?: return
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                if (!running) return else { sleep(500); continue }
            }
            val from = packet.socketAddress as? InetSocketAddress ?: continue
            if (from.address != target?.address) continue
            lastReceived = System.currentTimeMillis()
            onPacket(buf, packet.length)
        }
    }

    override fun send(data: ByteArray) {
        val t = target ?: return
        try { socket?.send(DatagramPacket(data, data.size, t)) } catch (_: Exception) {}
    }

    override fun keepalive() {} // o HELLO periódico já mantém a "porta" aberta na operadora

    override fun close() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { thread?.join(1500) } catch (_: Exception) {}
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}

/**
 * Cliente WebSocket mínimo (RFC 6455), sem bibliotecas externas.
 * Reconecta sozinho se a conexão cair (túnel, troca de antena, 4G oscilando).
 */
class WsLink(
    private val secure: Boolean,
    private val host: String,
    private val port: Int,
    private val path: String,
    private val onPacket: (ByteArray, Int) -> Unit,
    private val onConnected: () -> Unit,
) : Link {
    override val helloKeepalive = false
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile override var connected = false; private set
    private val writeLock = Any()
    private val random = SecureRandom()
    private var thread: Thread? = null

    override fun start() {
        running = true
        thread = Thread({ loop() }, "ws-link").also { it.start() }
    }

    private fun loop() {
        var backoff = 1000L
        while (running) {
            try {
                open()
                backoff = 1000L
                connected = true
                onConnected()
                readFrames(socket!!.getInputStream())
            } catch (_: Exception) {
            } finally {
                connected = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                out = null
            }
            if (!running) return
            try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
            backoff = minOf(backoff * 2, 8000L)
        }
    }

    private fun open() {
        val s = if (secure) SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
        if (s is SSLSocket) {
            // O SSLSocket puro confere a cadeia do certificado, mas não o nome do servidor.
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) {
                s.close()
                throw IllegalStateException("certificado não confere com $host")
            }
        }
        s.tcpNoDelay = true
        s.soTimeout = 40000
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val hostHeader = if ((secure && port == 443) || (!secure && port == 80)) host else "$host:$port"
        val req = "GET $path HTTP/1.1\r\n" +
            "Host: $hostHeader\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "User-Agent: Comunicador\r\n\r\n"
        val o = s.getOutputStream()
        o.write(req.toByteArray(Charsets.US_ASCII))
        o.flush()

        val input = s.getInputStream()
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c < 0) throw IllegalStateException("conexão fechada")
            head.append(c.toChar())
            if (head.length > 8192) throw IllegalStateException("resposta grande demais")
        }
        val lines = head.split("\r\n")
        if (!lines[0].contains(" 101")) throw IllegalStateException("servidor recusou: ${lines[0]}")
        val expected = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        )
        val accept = lines.firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
        if (accept != expected) throw IllegalStateException("resposta inválida")
        socket = s
        out = o
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var n = 0
        while (n < len) {
            val r = input.read(buf, n, len - n)
            if (r < 0) throw IllegalStateException("conexão fechada")
            n += r
        }
    }

    private fun readFrames(input: InputStream) {
        val head = ByteArray(8)
        var payload = ByteArray(4096)
        var message = java.io.ByteArrayOutputStream()
        while (running) {
            readFully(input, head, 2)
            val fin = head[0].toInt() and 0x80 != 0
            val opcode = head[0].toInt() and 0x0f
            val masked = head[1].toInt() and 0x80 != 0
            var len = (head[1].toInt() and 0x7f).toLong()
            if (len == 126L) {
                readFully(input, head, 2)
                len = (((head[0].toInt() and 0xff) shl 8) or (head[1].toInt() and 0xff)).toLong()
            } else if (len == 127L) {
                readFully(input, head, 8)
                len = 0
                for (i in 0 until 8) len = (len shl 8) or (head[i].toLong() and 0xff)
            }
            if (len > 1_000_000) throw IllegalStateException("mensagem grande demais")
            val mask = ByteArray(4)
            if (masked) readFully(input, mask, 4)
            val n = len.toInt()
            if (payload.size < n) payload = ByteArray(n)
            readFully(input, payload, n)
            if (masked) for (i in 0 until n) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

            when (opcode) {
                0x1, 0x2, 0x0 -> {
                    if (opcode != 0 && fin) {
                        onPacket(payload, n)
                    } else {
                        message.write(payload, 0, n)
                        if (fin) {
                            val m = message.toByteArray()
                            message = java.io.ByteArrayOutputStream()
                            onPacket(m, m.size)
                        }
                    }
                }
                0x8 -> { writeFrame(0x8, ByteArray(0)); return } // servidor fechou
                0x9 -> writeFrame(0xA, payload.copyOf(n))         // ping -> pong
                0xA -> {}                                         // pong
            }
        }
    }

    private fun writeFrame(opcode: Int, data: ByteArray) {
        val o = out ?: return
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val len = data.size
        val header = when {
            len < 126 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or len).toByte())
            len < 65536 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or 126).toByte(), (len shr 8).toByte(), len.toByte())
            else -> return
        }
        val frame = ByteArray(header.size + 4 + len)
        System.arraycopy(header, 0, frame, 0, header.size)
        System.arraycopy(mask, 0, frame, header.size, 4)
        val base = header.size + 4
        for (i in 0 until len) frame[base + i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        try {
            synchronized(writeLock) {
                o.write(frame)
                o.flush()
            }
        } catch (_: Exception) {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    override fun send(data: ByteArray) {
        if (connected) writeFrame(0x2, data)
    }

    /** Ping do próprio WebSocket: mantém a conexão viva e não conta como mensagem cobrada na Cloudflare. */
    override fun keepalive() {
        if (connected) writeFrame(0x9, ByteArray(0))
    }

    override fun close() {
        running = false
        if (connected) writeFrame(0x8, byteArrayOf(0x03, 0xE8.toByte()))
        try { socket?.close() } catch (_: Exception) {}
        try { thread?.join(1500) } catch (_: Exception) {}
    }
}
