package br.comunicador.set

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Teste de áudio antes da diária: grava 3 segundos e toca de volta pelo mesmo
 * caminho que o comunicador usa (mesmo microfone, mesmo fone). Diz também por
 * qual microfone e por qual saída o som passou, e avisa se não chegou som nenhum.
 */
class AudioTest(
    context: Context,
    private val useBtMic: Boolean,
    private val onLevel: (Double) -> Unit,
    private val onStage: (Stage, String) -> Unit,
) {
    enum class Stage { RECORDING, PLAYING, DONE, ERROR }

    companion object {
        private const val SECONDS = 3
        /** Abaixo disso (média do trecho mais alto), consideramos que não chegou voz. */
        private const val SILENCE = 150.0
    }

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false

    fun start() {
        Thread({ run() }, "teste-audio").start()
    }

    fun cancel() {
        cancelled = true
    }

    private fun stage(s: Stage, msg: String) = main.post { onStage(s, msg) }

    private fun run() {
        val previousMode = audioManager.mode
        try {
            if (useBtMic) routeToBluetooth(true)
            val samples = record() ?: return
            if (cancelled) return
            val peak = loudestStretch(samples)
            stage(Stage.PLAYING, "Tocando o que você falou…")
            play(samples)
            if (cancelled) return

            val route = "Microfone: ${inputName()} · Saída: ${outputName()}"
            if (peak < SILENCE) {
                stage(Stage.ERROR, "Não chegou som do microfone. Confira se ele não está tapado, se o fone está conectado e se o app tem permissão de microfone.\n$route")
            } else {
                stage(Stage.DONE, "Pronto. Se você se ouviu com clareza, está tudo certo para a diária.\n$route")
            }
        } catch (e: Exception) {
            stage(Stage.ERROR, "O teste não funcionou: ${e.message ?: "erro desconhecido"}")
        } finally {
            if (useBtMic) routeToBluetooth(false)
            try { audioManager.mode = previousMode } catch (_: Exception) {}
            main.post { onLevel(0.0) }
        }
    }

    // ---------------------------------------------------------------------
    // Gravar e tocar
    // ---------------------------------------------------------------------

    private fun record(): ShortArray? {
        val minBuf = AudioRecord.getMinBufferSize(Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, Protocol.FRAME * 2 * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stage(Stage.ERROR, "O microfone está ocupado ou sem permissão. Feche outros apps que usem o microfone e tente de novo.")
            return null
        }
        val total = Protocol.SAMPLE_RATE * SECONDS
        val out = ShortArray(total)
        stage(Stage.RECORDING, "Fale agora, no tom normal do set… (3 segundos)")
        try {
            rec.startRecording()
            var n = 0
            while (n < total && !cancelled) {
                val chunk = minOf(Protocol.FRAME, total - n)
                val r = rec.read(out, n, chunk)
                if (r <= 0) break
                val level = Protocol.rms(out.copyOfRange(n, n + r))
                main.post { onLevel(level) }
                n += r
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return out
    }

    private fun play(samples: ShortArray) {
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
        val track = AudioTrack(attrs, format, maxOf(minBuf, Protocol.FRAME * 2 * 4), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        try {
            track.play()
            var i = 0
            while (i < samples.size && !cancelled) {
                val n = minOf(Protocol.FRAME, samples.size - i)
                track.write(samples, i, n) // bloqueia no ritmo do áudio
                main.post { onLevel(Protocol.rms(samples.copyOfRange(i, i + n))) }
                i += n
            }
            Thread.sleep(250) // deixa o finalzinho sair do alto-falante
        } finally {
            try { track.stop() } catch (_: Exception) {}
            track.release()
        }
    }

    /** Nível do trecho de 200 ms mais alto: ignora o silêncio antes e depois da fala. */
    private fun loudestStretch(samples: ShortArray): Double {
        val win = Protocol.SAMPLE_RATE / 5
        var best = 0.0
        var i = 0
        while (i + win <= samples.size) {
            best = maxOf(best, Protocol.rms(samples.copyOfRange(i, i + win)))
            i += win / 2
        }
        return best
    }

    // ---------------------------------------------------------------------
    // Por onde o som passou
    // ---------------------------------------------------------------------

    private fun routeToBluetooth(on: Boolean) {
        try {
            if (on) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
                Thread.sleep(800) // o fone leva um instante para abrir o microfone
            } else {
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
            }
        } catch (_: Exception) {}
    }

    private fun devices(flag: Int): List<AudioDeviceInfo> =
        try { audioManager.getDevices(flag).toList() } catch (_: Exception) { emptyList() }

    private fun inputName(): String {
        val inputs = devices(AudioManager.GET_DEVICES_INPUTS)
        val bt = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        val wired = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        return when {
            useBtMic && bt != null -> "do fone Bluetooth (${bt.productName})"
            useBtMic -> "do celular (o fone Bluetooth não abriu o microfone)"
            wired != null -> "do fone com fio"
            else -> "do celular"
        }
    }

    private fun outputName(): String {
        val outs = devices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        val wired = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return when {
            bt != null -> "fone Bluetooth (${bt.productName})"
            wired != null -> "fone com fio"
            else -> "alto-falante do celular (sem fone conectado)"
        }
    }
}
