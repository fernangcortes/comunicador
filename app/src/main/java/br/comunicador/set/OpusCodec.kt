package br.comunicador.set

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus pelo codificador embutido do Android (MediaCodec).
 * Codificar: Android 10 ou mais novo. Decodificar: todos os aparelhos suportados pelo app.
 * Se algo falhar, o app volta sozinho para o ADPCM.
 *
 * Voz a 16 kHz, mono, 24 kbit/s, um pacote a cada 20 ms.
 */
object Opus {
    const val BITRATE = 24000

    /** Cabeçalho "OpusHead" que o decodificador do Android exige (RFC 7845). */
    fun opusHead(sampleRate: Int): ByteArray {
        val bb = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("OpusHead".toByteArray(Charsets.US_ASCII))
        bb.put(1)                 // versão
        bb.put(1)                 // canais
        bb.putShort(0)            // pre-skip
        bb.putInt(sampleRate)     // taxa original
        bb.putShort(0)            // ganho
        bb.put(0)                 // mapeamento
        return bb.array()
    }

    fun longLE(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
}

class OpusEncoder private constructor(private val codec: MediaCodec) {
    companion object {
        /** Devolve null quando o aparelho não tem codificador Opus. */
        fun createOrNull(): OpusEncoder? {
            if (Build.VERSION.SDK_INT < 29) return null
            return try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Protocol.SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, Opus.BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Protocol.FRAME * 2 * 4)
                }
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                OpusEncoder(c)
            } catch (_: Exception) {
                null
            }
        }
    }

    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L
    private val bytes = ByteArray(Protocol.FRAME * 2)

    /** Codifica 20 ms. Devolve os pacotes prontos (normalmente um; às vezes nenhum no começo). */
    fun encode(pcm: ShortArray): List<ByteArray> {
        val inIndex = codec.dequeueInputBuffer(5000)
        if (inIndex >= 0) {
            val buf = codec.getInputBuffer(inIndex)!!
            buf.clear()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, minOf(pcm.size, Protocol.FRAME))
            buf.put(bytes, 0, pcm.size * 2)
            codec.queueInputBuffer(inIndex, 0, pcm.size * 2, ptsUs, 0)
            ptsUs += pcm.size * 1_000_000L / Protocol.SAMPLE_RATE
        }
        val out = ArrayList<ByteArray>(1)
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            val buf = codec.getOutputBuffer(outIndex)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buf != null && info.size > 0 && !isConfig) {
                val packet = ByteArray(info.size)
                buf.position(info.offset)
                buf.get(packet)
                out.add(packet)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
        return out
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }
}

/** Um decodificador Opus por pessoa que fala; entrega blocos de 20 ms a 16 kHz para o mixer. */
class OpusDecoders {
    private class Dec(val codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        var outRate = 48000
        val pending = ArrayList<Short>(Protocol.FRAME * 2)
        var lastUsed = System.currentTimeMillis()
    }

    private val decoders = HashMap<Int, Dec>()

    @Synchronized
    fun decode(id: Int, data: ByteArray, off: Int, len: Int): List<ShortArray> {
        val d = decoders[id] ?: create()?.also { decoders[id] = it } ?: return emptyList()
        d.lastUsed = System.currentTimeMillis()
        return try {
            feed(d, data, off, len)
        } catch (_: Exception) {
            decoders.remove(id)?.let { release(it) }
            emptyList()
        }
    }

    /** Fecha decodificadores de quem parou de falar há mais de 1 minuto. */
    @Synchronized
    fun cleanup() {
        val now = System.currentTimeMillis()
        val old = decoders.filterValues { now - it.lastUsed > 60_000 }
        for ((id, d) in old) { release(d); decoders.remove(id) }
    }

    @Synchronized
    fun releaseAll() {
        decoders.values.forEach { release(it) }
        decoders.clear()
    }

    private fun create(): Dec? = try {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Protocol.SAMPLE_RATE, 1).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(Opus.opusHead(Protocol.SAMPLE_RATE)))
            setByteBuffer("csd-1", ByteBuffer.wrap(Opus.longLE(0)))          // pre-skip em ns
            setByteBuffer("csd-2", ByteBuffer.wrap(Opus.longLE(80_000_000)))  // seek pre-roll em ns
        }
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        c.configure(format, null, null, 0)
        c.start()
        Dec(c)
    } catch (_: Exception) {
        null
    }

    private fun feed(d: Dec, data: ByteArray, off: Int, len: Int): List<ShortArray> {
        val inIndex = d.codec.dequeueInputBuffer(5000)
        if (inIndex >= 0) {
            val buf = d.codec.getInputBuffer(inIndex)!!
            buf.clear()
            buf.put(data, off, len)
            d.codec.queueInputBuffer(inIndex, 0, len, d.ptsUs, 0)
            d.ptsUs += 20_000
        }
        val frames = ArrayList<ShortArray>(1)
        while (true) {
            val outIndex = d.codec.dequeueOutputBuffer(d.info, 2000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                d.outRate = d.codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                continue
            }
            if (outIndex < 0) break
            val buf = d.codec.getOutputBuffer(outIndex)
            if (buf != null && d.info.size > 0) {
                buf.position(d.info.offset)
                val sb = buf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val n = d.info.size / 2
                val raw = ShortArray(n)
                sb.get(raw, 0, n)
                resampleInto(raw, d.outRate, d.pending)
            }
            d.codec.releaseOutputBuffer(outIndex, false)
        }
        // Entrega em blocos de exatamente 20 ms.
        while (d.pending.size >= Protocol.FRAME) {
            val f = ShortArray(Protocol.FRAME) { d.pending[it] }
            d.pending.subList(0, Protocol.FRAME).clear()
            frames.add(f)
        }
        return frames
    }

    /** Converte a taxa de saída do decodificador (geralmente 48 kHz) para os 16 kHz do mixer. */
    private fun resampleInto(src: ShortArray, rate: Int, dst: MutableList<Short>) {
        if (rate == Protocol.SAMPLE_RATE) { src.forEach { dst.add(it) }; return }
        val step = rate.toDouble() / Protocol.SAMPLE_RATE
        var pos = 0.0
        while (pos < src.size) {
            // média simples da janela: evita chiado ao reduzir a taxa
            val start = pos.toInt()
            val end = minOf(src.size, (pos + step).toInt().coerceAtLeast(start + 1))
            var sum = 0
            for (i in start until end) sum += src[i]
            dst.add((sum / (end - start)).toShort())
            pos += step
        }
    }

    private fun release(d: Dec) {
        try { d.codec.stop() } catch (_: Exception) {}
        try { d.codec.release() } catch (_: Exception) {}
    }
}
