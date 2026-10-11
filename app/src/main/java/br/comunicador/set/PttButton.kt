package br.comunicador.set

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Botões PTT Bluetooth "compatíveis com Zello" (Focket PTT-Z01, Pryme BT-PTT-Z e genéricos).
 * Eles usam um perfil BLE no estilo HM-10: serviço FFE0, característica FFE1 com notificação.
 * Ao apertar mandam "+PTT=P" (ou "+PTTS=P"); ao soltar, "+PTT=R" (ou "+PTTS=R").
 * Alguns mandam um byte com bitmask (0x01 = botão principal apertado). Os dois formatos são aceitos.
 */
@SuppressLint("MissingPermission")
class PttButton(
    private val context: Context,
    private val onPress: (Boolean) -> Unit,
) {
    enum class State { OFF, CONNECTING, READY }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Interpreta a mensagem do botão: true = apertado, false = solto, null = ignorar. */
        fun parse(value: ByteArray): Boolean? {
            if (value.isEmpty()) return null
            val text = String(value, Charsets.US_ASCII).trim().uppercase()
            if (text.startsWith("+PTT")) {
                return when {
                    text.endsWith("=P") -> true
                    text.endsWith("=R") -> false
                    else -> null
                }
            }
            if (value.size == 1) return (value[0].toInt() and 0x01) != 0
            return null
        }
    }

    @Volatile var state = State.OFF; private set
    @Volatile var name: String = ""; private set

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var pressed = false
    private var stopped = true

    fun connect(address: String, deviceName: String) {
        disconnect()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val device: BluetoothDevice = try { adapter.getRemoteDevice(address) } catch (_: Exception) { return }
        name = deviceName.ifEmpty { address }
        stopped = false
        state = State.CONNECTING
        try {
            // autoConnect = true: o Android reconecta sozinho quando o botão acorda.
            gatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            state = State.OFF
        }
    }

    fun disconnect() {
        stopped = true
        release()
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        state = State.OFF
    }

    private fun setPressed(p: Boolean) {
        if (p == pressed) return
        pressed = p
        onPress(p)
    }

    /** Se o botão cair no meio de uma fala, solta para não ficar transmitindo. */
    private fun release() = setPressed(false)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post { if (!stopped) g.discoverServices() }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    release()
                    if (!stopped) state = State.CONNECTING
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
            if (ch == null) {
                state = State.CONNECTING
                return
            }
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD)
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(desc)
                }
            }
            state = State.READY
        }

        // Android 13 em diante
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handle(ch, value)
        }

        // Android 12 e anteriores
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            handle(ch, ch.value ?: return)
        }

        private fun handle(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != CHARACTERISTIC) return
            val p = parse(value) ?: return
            main.post { setPressed(p) }
        }
    }
}

/** Busca botões por perto, para a tela de parear. */
@SuppressLint("MissingPermission")
class PttScanner(private val context: Context) {
    data class Found(val address: String, val name: String, val likelyPtt: Boolean)

    private var callback: android.bluetooth.le.ScanCallback? = null
    private val main = Handler(Looper.getMainLooper())

    fun isBluetoothOn(): Boolean =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    fun start(onFound: (Found) -> Unit, onDone: () -> Unit) {
        stop()
        val adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return onDone()
        val scanner = adapter.bluetoothLeScanner ?: return onDone()
        val cb = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(type: Int, result: android.bluetooth.le.ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
                val likely = PttButton.SERVICE in uuids ||
                    listOf("PTT", "ZELLO", "PRYME", "TALK").any { name.uppercase().contains(it) }
                onFound(Found(result.device.address, name, likely))
            }
            override fun onScanFailed(errorCode: Int) { onDone() }
        }
        callback = cb
        try { scanner.startScan(cb) } catch (_: Exception) { return onDone() }
        main.postDelayed({ stop(); onDone() }, 15000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        val cb = callback ?: return
        callback = null
        try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
    }
}
