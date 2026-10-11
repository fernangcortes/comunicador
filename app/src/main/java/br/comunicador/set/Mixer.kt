package br.comunicador.set

import android.os.SystemClock

/**
 * Recebe blocos de voz de cada pessoa, segura um pouquinho (60 ms) para absorver
 * as oscilações do Wi-Fi, mistura todo mundo e entrega 20 ms por vez para o alto-falante.
 * Também toca o bipe de rádio quando alguém começa ou termina de falar.
 */
class Mixer {
    private class Stream {
        val queue = ArrayDeque<ShortArray>()
        var started = false
        var lastPush = 0L
    }

    private val lock = Any()
    private val streams = HashMap<Int, Stream>()
    private val beeps = ArrayDeque<ShortArray>()
    private val acc = IntArray(Protocol.FRAME)
    private val duckAcc = IntArray(Protocol.FRAME)

    /** Pessoas com prioridade (direção/produção): quando falam, as outras vozes abaixam. */
    @Volatile var priorityIds: Set<Int> = emptySet()

    companion object {
        private const val PREBUFFER = 3      // 60 ms antes de começar a tocar
        private const val MAX_QUEUE = 12     // acima de 240 ms acumulados, descarta para não atrasar
        private const val END_GAP_MS = 300L  // sem pacotes por 300 ms = a pessoa parou de falar
        const val BEEP_START = 1200
        const val BEEP_END = 800
    }

    fun push(id: Int, pcm: ShortArray) {
        synchronized(lock) {
            val s = streams.getOrPut(id) { Stream() }
            s.lastPush = SystemClock.elapsedRealtime()
            s.queue.addLast(pcm)
            while (s.queue.size > MAX_QUEUE) s.queue.removeFirst()
        }
    }

    fun beep(freq: Int) {
        synchronized(lock) { addBeep(freq) }
    }

    fun clear() {
        synchronized(lock) {
            streams.clear()
            beeps.clear()
        }
    }

    private fun addBeep(freq: Int) {
        // 80 ms de tom com rampa curta para não estalar
        val total = Protocol.SAMPLE_RATE * 80 / 1000
        val tone = ShortArray(total)
        for (i in 0 until total) {
            val env = minOf(1.0, i / 80.0, (total - i) / 80.0)
            tone[i] = (Math.sin(2 * Math.PI * freq * i / Protocol.SAMPLE_RATE) * 3500 * env).toInt().toShort()
        }
        var i = 0
        while (i < total) {
            val frame = ShortArray(Protocol.FRAME)
            System.arraycopy(tone, i, frame, 0, minOf(Protocol.FRAME, total - i))
            beeps.addLast(frame)
            i += Protocol.FRAME
        }
    }

    /** Preenche [out] com os próximos 20 ms de áudio misturado. */
    fun nextFrame(out: ShortArray) {
        java.util.Arrays.fill(acc, 0)
        java.util.Arrays.fill(duckAcc, 0)
        var priorityActive = false
        val prio = priorityIds
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            val it = streams.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val id = entry.key
                val s = entry.value
                if (!s.started) {
                    val idle = now - s.lastPush
                    if (s.queue.size >= PREBUFFER || (s.queue.isNotEmpty() && idle > 100)) {
                        s.started = true
                        addBeep(BEEP_START)
                    } else {
                        if (s.queue.isEmpty() && idle > 1000) it.remove()
                        continue
                    }
                }
                val f = s.queue.removeFirstOrNull()
                if (f != null) {
                    // Voz com prioridade vai inteira; as outras ficam separadas para poder abaixar.
                    val isPrio = id in prio
                    if (isPrio) priorityActive = true
                    val target = if (isPrio) acc else duckAcc
                    for (i in 0 until minOf(f.size, target.size)) target[i] += f[i].toInt()
                } else if (now - s.lastPush > END_GAP_MS) {
                    it.remove()
                    addBeep(BEEP_END)
                }
            }
            beeps.removeFirstOrNull()?.let { b -> for (i in acc.indices) acc[i] += b[i].toInt() }
        }
        // Quando a direção/produção fala, as outras vozes caem para 25%.
        val gain = if (priorityActive) 0.25 else 1.0
        for (i in out.indices) out[i] = (acc[i] + (duckAcc[i] * gain).toInt()).coerceIn(-32768, 32767).toShort()
    }
}
