package br.comunicador.set

/**
 * Compressão de voz IMA ADPCM: 4 bits por amostra, 4x menor que o PCM de 16 bits.
 * Cada bloco leva no cabeçalho o estado do codificador (preditor + índice),
 * então um pacote perdido não estraga os seguintes.
 *
 * Bloco: [preditor i16 LE][índice u8][reservado u8][amostras, 2 por byte, nibble baixo primeiro]
 */
class ImaAdpcm {
    companion object {
        const val HEADER = 4

        private val STEP = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
            253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
            3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
            12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
        )
        private val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        fun encodedSize(samples: Int) = HEADER + (samples + 1) / 2

        fun decode(src: ByteArray, off: Int, len: Int): ShortArray {
            if (len < HEADER) return ShortArray(0)
            var pred = ((src[off].toInt() and 0xff) or (src[off + 1].toInt() shl 8)).toShort().toInt()
            var idx = (src[off + 2].toInt() and 0xff).coerceIn(0, 88)
            val n = (len - HEADER) * 2
            val out = ShortArray(n)
            for (i in 0 until n) {
                val b = src[off + HEADER + i / 2].toInt()
                val nib = if (i % 2 == 0) b and 0x0f else (b shr 4) and 0x0f
                val step = STEP[idx]
                var vp = step shr 3
                if (nib and 4 != 0) vp += step
                if (nib and 2 != 0) vp += step shr 1
                if (nib and 1 != 0) vp += step shr 2
                pred = if (nib and 8 != 0) pred - vp else pred + vp
                pred = pred.coerceIn(-32768, 32767)
                idx = (idx + INDEX[nib]).coerceIn(0, 88)
                out[i] = pred.toShort()
            }
            return out
        }
    }

    private var pred = 0
    private var idx = 0

    fun reset() {
        pred = 0
        idx = 0
    }

    /** Codifica [pcm] e grava o bloco em [dst] a partir de [off]. Devolve o número de bytes escritos. */
    fun encode(pcm: ShortArray, dst: ByteArray, off: Int): Int {
        dst[off] = (pred and 0xff).toByte()
        dst[off + 1] = ((pred shr 8) and 0xff).toByte()
        dst[off + 2] = idx.toByte()
        dst[off + 3] = 0
        var pos = off + HEADER
        var pending = -1
        for (s in pcm) {
            var step = STEP[idx]
            var diff = s - pred
            var nib = 0
            if (diff < 0) { nib = 8; diff = -diff }
            var vp = step shr 3
            if (diff >= step) { nib = nib or 4; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { nib = nib or 2; diff -= step; vp += step }
            step = step shr 1
            if (diff >= step) { nib = nib or 1; vp += step }
            pred = (if (nib and 8 != 0) pred - vp else pred + vp).coerceIn(-32768, 32767)
            idx = (idx + INDEX[nib]).coerceIn(0, 88)
            if (pending < 0) {
                pending = nib
            } else {
                dst[pos++] = (pending or (nib shl 4)).toByte()
                pending = -1
            }
        }
        if (pending >= 0) dst[pos++] = pending.toByte()
        return pos - off
    }
}
