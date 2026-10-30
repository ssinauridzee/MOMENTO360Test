package ge.momento.booth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * 360Tok-00772 controller extracted from the verified official 360Tok iPad traffic.
 * Characteristic FFE1 WRITE_NO_RESPONSE, 8-byte 88 99 op value 00 00 00 checksum.
 * iPad logs and verified Android v3.1 motor direction: normal=03 01, reverse=03 00.
 */
@SuppressLint("MissingPermission")
class Tok360Ble(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onData: (connected: Boolean, motorOn: Boolean?, speed: Int?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val uuidService = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val uuidFFE1 = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val uuidCCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val pending = ArrayDeque<ByteArray>()
    private var pumpTask: Runnable? = null
    private var scanTimeout: Runnable? = null
    var connected = false
        private set
    var motorOn: Boolean? = null
        private set
    var speed: Int? = null
        private set

    fun connect() {
        if (!adapter.isEnabled) { onStatus("ჩართე Bluetooth"); return }
        disconnect()
        val bleScanner = adapter.bluetoothLeScanner ?: run { onStatus("BLE ვერ ჩაირთო"); return }
        scanner = bleScanner
        onStatus("360Tok-ის ძებნა…")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: ""
                val serviceMatch = result.scanRecord?.serviceUuids?.any { it.uuid == uuidService } ?: false
                if (!name.startsWith("360Tok-", ignoreCase = true) && !serviceMatch) return
                stopScan()
                onStatus("360Tok-თან დაკავშირება…")
                gatt = result.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) {
                stopScan(); onStatus("360Tok ძებნის შეცდომა: $errorCode")
            }
        }
        scanCallback = cb
        bleScanner.startScan(cb)
        scanTimeout = Runnable {
            if (scanCallback === cb) { stopScan(); onStatus("360Tok ვერ მოიძებნა") }
        }.also { main.postDelayed(it, 17000) }
    }
    private fun stopScan() {
        scanTimeout?.let { main.removeCallbacks(it) }; scanTimeout = null
        val cb = scanCallback; scanCallback = null
        if (cb != null) runCatching { scanner?.stopScan(cb) }
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            main.post {
                if (gatt !== g) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("360Tok სერვისების შემოწმება…")
                    g.discoverServices()
                } else {
                    connected = false; writeChar = null; motorOn = null; speed = null
                    pending.clear(); pumpTask?.let { main.removeCallbacks(it) }; pumpTask = null
                    onData(false, null, null)
                    onStatus("360Tok გათიშულია ($status)")
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (gatt !== g) return@post
                val chr = g.getService(uuidService)?.getCharacteristic(uuidFFE1)
                if (status != BluetoothGatt.GATT_SUCCESS || chr == null) {
                    onStatus("FFE1 მართვის არხი ვერ მოიძებნა"); return@post
                }
                writeChar = chr
                g.setCharacteristicNotification(chr, true)
                val d = chr.getDescriptor(uuidCCCD)
                if (d == null) {
                    connected = true; onData(true, motorOn, speed); onStatus("360Tok მზადაა")
                } else {
                    val enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val started = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(d, enabled) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        d.value = enabled
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(d)
                    }
                    if (!started) onStatus("FFE1 სტატუსი ვერ გააქტიურდა")
                }
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (gatt !== g) return@post
                connected = status == BluetoothGatt.GATT_SUCCESS
                onData(connected, motorOn, speed)
                onStatus(if (connected) "360Tok მზადაა" else "360Tok სერვისის შეცდომა: $status")
            }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == uuidFFE1) statusPacket(value)
        }
        @Deprecated("pre Android 13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == uuidFFE1) {
                @Suppress("DEPRECATION") val bytes = characteristic.value
                if (bytes != null) statusPacket(bytes)
            }
        }
    }
    private fun statusPacket(bytes: ByteArray) {
        if (bytes.size < 13 || (bytes[0].toInt() and 255) != 0x11 || (bytes[1].toInt() and 255) != 0x22) return
        val m = bytes[2].toInt() and 255
        val s = bytes[4].toInt() and 255
        main.post {
            if (m == 0 || m == 1) motorOn = m == 1
            if (s in 1..6) speed = s
            onData(connected, motorOn, speed)
        }
    }
    private fun packet(op: Int, value: Int): ByteArray {
        val data = byteArrayOf(0x88.toByte(), 0x99.toByte(), op.toByte(), value.toByte(), 0, 0, 0, 0)
        var sum = 0
        for (i in 0..6) sum += data[i].toInt() and 255
        data[7] = (sum and 255).toByte()
        return data
    }
    fun send(op: Int, value: Int) {
        if (!connected || writeChar == null || gatt == null) {
            onStatus("360Tok ჯერ დაკავშირებული არ არის"); return
        }
        pending.addLast(packet(op, value))
        pump()
    }
    fun start(durationSeconds: Int) {
        if (!connected) return
        send(4, durationSeconds.coerceIn(0, 60))
        send(1, 1)
    }
    fun stop() {
        // Drop a pending START to prioritize the physical stop request.
        pending.clear()
        send(1, 0)
    }
    private fun pump() {
        if (pumpTask != null || pending.isEmpty()) return
        val job = Runnable {
            pumpTask = null
            if (!connected) { pending.clear(); return@Runnable }
            val bytes = pending.pollFirst() ?: return@Runnable
            val g = gatt; val c = writeChar
            if (g != null && c != null) {
                try {
                    val ok = if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION") c.value = bytes
                        @Suppress("DEPRECATION") g.writeCharacteristic(c)
                    }
                    if (!ok) Log.w("MOMENTO360", "FFE1 BLE write failed")
                } catch (e: Exception) { Log.e("MOMENTO360", "FFE1 write", e) }
            }
            if (pending.isNotEmpty()) {
                // Write-without-response commands must be spaced apart on this controller.
                pumpTask = Runnable { pumpTask = null; pump() }
                main.postDelayed(pumpTask!!, 220)
            }
        }
        pumpTask = job
        main.post(job)
    }
    fun disconnect() {
        stopScan(); pending.clear()
        pumpTask?.let { main.removeCallbacks(it) }; pumpTask = null
        connected = false; writeChar = null; motorOn = null; speed = null
        onData(false, null, null)
        gatt?.let { runCatching { it.disconnect(); it.close() } }; gatt = null
    }
}
