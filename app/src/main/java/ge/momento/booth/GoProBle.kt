package ge.momento.booth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID
import java.util.ArrayDeque

@SuppressLint("MissingPermission")
class GoProBle(private val context: Context, private val status: (String) -> Unit, private val ready: (Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val service = UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb")
    private val requestId = UUID.fromString("b5f90072-aa8d-11e3-9046-0002a5d5c51b")
    private val responseId = UUID.fromString("b5f90073-aa8d-11e3-9046-0002a5d5c51b")
    private val settingId = UUID.fromString("b5f90074-aa8d-11e3-9046-0002a5d5c51b")
    private val settingResponseId = UUID.fromString("b5f90075-aa8d-11e3-9046-0002a5d5c51b")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private var gatt: BluetoothGatt? = null
    private var request: BluetoothGattCharacteristic? = null
    private var settingRequest: BluetoothGattCharacteristic? = null
    private var settingResponse: BluetoothGattCharacteristic? = null
    private var settingEnabled = false
    private var scan: ScanCallback? = null
    private data class Command(val id: Int, val value: Int, val label: String, val result: (Boolean) -> Unit, val isSetting: Boolean = false)
    private val pending = ArrayDeque<Command>()
    private var inflight: Command? = null
    private var configured = false

    fun scanAndConnect() {
        if (!adapter.isEnabled) { status("ჩართე Bluetooth"); return }
        disconnect()
        status("GoPro-ს ძებნა… ჩართე კამერაზე Pairing რეჟიმი")
        val scanner = adapter.bluetoothLeScanner ?: run { status("BLE scanner მიუწვდომელია"); return }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
                if (!name.contains("GoPro", ignoreCase = true)) return
                scanner.stopScan(this)
                scan = null
                status("ნაპოვნია $name — დაკავშირება…")
                gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) { status("სკანირების შეცდომა: $errorCode") }
        }
        scan = callback
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        handler.postDelayed({ if (scan === callback) { scanner.stopScan(callback); scan = null; status("GoPro ვერ მოიძებნა; გადაამოწმე Pairing") } }, 12000)
    }

    fun disconnect() {
        scan?.let { adapter.bluetoothLeScanner?.stopScan(it) }; scan = null
        gatt?.disconnect(); gatt?.close(); gatt = null
        request = null; settingRequest = null; settingResponse=null; settingEnabled=false; configured = false; ready(false)
        pending.clear(); inflight = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                status("დაკავშირებულია; სერვისების შემოწმება…")
                g.discoverServices()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                configured = false; request = null; ready(false); status("კამერასთან კავშირი გაწყდა ($code)")
                inflight?.result?.invoke(false); inflight = null
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            val s = g.getService(service)
            val r = s?.getCharacteristic(responseId)
            request = s?.getCharacteristic(requestId)
            settingRequest = s?.getCharacteristic(settingId)
            settingResponse = s?.getCharacteristic(settingResponseId)
            if (code != BluetoothGatt.GATT_SUCCESS || r == null || request == null) { status("მართვის BLE სერვისი ვერ მოიძებნა"); return }
            if (!g.setCharacteristicNotification(r, true)) { status("Notification ვერ გააქტიურდა"); return }
            val d = r.getDescriptor(cccd) ?: run { status("CCCD ვერ მოიძებნა"); return }
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val ok = g.writeDescriptor(d)
            if (!ok) status("Notification-ის გამოწერა ვერ შესრულდა")
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, code: Int) {
            if (d.uuid == cccd && code == BluetoothGatt.GATT_SUCCESS) {
                if (d.characteristic.uuid == responseId) {
                    configured = true; ready(true); status("GoPro მზად არის. კამერა დააყენე Video რეჟიმზე")
                    // Subscribe to settings responses after the command descriptor has completed.
                    val sr = settingResponse
                    if (sr != null && settingRequest != null && g.setCharacteristicNotification(sr, true)) {
                        val sd = sr.getDescriptor(cccd)
                        if (sd != null) {
                            @Suppress("DEPRECATION")
                            sd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            val ok = g.writeDescriptor(sd)
                            if (!ok) status("GoPro ვიდეო პარამეტრების პასუხის არხი მიუწვდომელია")
                        }
                    }
                } else if (d.characteristic.uuid == settingResponseId) {
                    settingEnabled = true
                    status("GoPro მზადაა — ვიდეო პარამეტრების შეცვლაც ხელმისაწვდომია")
                }
            } else status("BLE გამოწერის შეცდომა: $code")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleResponse(characteristic.uuid,value) }
        @Deprecated("Compatibility with Android versions below 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (android.os.Build.VERSION.SDK_INT < 33) handleResponse(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { val item = inflight; inflight = null; item?.result?.invoke(false); next() }
        }
    }
    private fun handleResponse(channel: UUID, data: ByteArray) {
        // The first byte is payload length, followed by command/setting ID and result code.
        val item = inflight ?: return
        if ((item.isSetting && channel != settingResponseId) || (!item.isSetting && channel != responseId)) return
        if (data.size < 3 || (data[1].toInt() and 255) != item.id) return
        inflight = null
        val success = data[2].toInt() == 0
        item.result(success)
        status(if (success) item.label else "GoPro-მ ბრძანება უარყო (${data[2].toInt() and 255})")
        next()
    }
    fun shutter(on: Boolean, result: (Boolean) -> Unit) {
        if (!configured) { result(false); status("GoPro დაკავშირებული არ არის"); return }
        pending.add(Command(1, if (on) 1 else 0, if (on) "გადაღება დაიწყო" else "გადაღება შეჩერდა", result)); next()
    }
    /** Official BLE Set AP Control command 0x17 / mode 1. Enable then wait for Wi-Fi AP. */
    fun enableWifiAp(result: (Boolean) -> Unit) {
        if (!configured) { result(false); status("ჯერ Bluetooth-ით დაუკავშირდი GoPro-ს"); return }
        pending.add(Command(0x17, 1, "GoPro Wi-Fi AP ჩართვის ბრძანება მიღებულია", result)); next()
    }
    /** Set a video setting only when HERO9 accepts its value in the current preset. */
    fun setVideoSetting(setting: Int, value: Int, label: String, result: (Boolean) -> Unit) {
        if (!configured || !settingEnabled || settingRequest == null) {
            status("GoPro ვიდეო პარამეტრების BLE არხი ჯერ არ არის მზად")
            result(false); return
        }
        pending.add(Command(setting,value,label,result,true))
        next()
    }
    private fun next() {
        if (inflight != null || pending.isEmpty()) return
        val item = pending.removeFirst(); inflight = item
        val bytes = byteArrayOf(3, item.id.toByte(), 1, item.value.toByte())
        val g = gatt; val c = if (item.isSetting) settingRequest else request
        if (g == null || c == null) { inflight = null; item.result(false); return }
        @Suppress("DEPRECATION")
        c.value = bytes
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        val started = g.writeCharacteristic(c)
        if (!started) { inflight = null; item.result(false); next(); return }
        handler.postDelayed({ if (inflight === item) { inflight = null; item.result(false); status("ბრძანებას კამერამ დროულად არ უპასუხა"); next() } }, 3500)
    }
}
