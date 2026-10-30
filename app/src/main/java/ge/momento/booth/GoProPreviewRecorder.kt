package ge.momento.booth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Experimental microSD-less capture for HERO9, using the CAMERA WIFI preview
 * MPEG-TS-over-UDP stream. NOT the GoPro shutter / high-FPS recording mode.
 *
 * Requires the Android handset to be associated with the GoPro AP (10.5.5.9).
 * Does not assert that HERO9 firmware enables preview when the SD is absent:
 * that must be established by an on-camera test. No silent success/fake video.
 */
class GoProPreviewRecorder(private val context: Context) {
    companion object {
        const val GOPRO_IP = "10.5.5.9"
        const val STREAM_PORT = 8554
    }
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var active = false

    fun isActive(): Boolean = active

    fun start(
        seconds: Double,
        state: (String) -> Unit,
        started: () -> Unit,
        finished: (Result<File>) -> Unit
    ) {
        if (active) { finished(Result.failure(IllegalStateException("ვიდეონაკადის ჩაწერა უკვე მიმდინარეობს"))); return }
        cancelled.set(false)
        active = true
        worker = thread(name = "momento-gopro-preview", isDaemon = true) {
            var file: File? = null
            var network: Network? = null
            var didStart = false
            try {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifi = manager.allNetworks.firstOrNull {
                    manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } ?: error("ტელეფონი Wi-Fi ქსელთან არ არის დაკავშირებული. გახსენი Wi-Fi პარამეტრები და დაუკავშირდი GoPro-ს ქსელს.")
                network = wifi
                sendStatus(state, "GoPro Wi-Fi შემოწმება…")
                val probe = httpGet(wifi, "/gopro/camera/state", 2200)
                if (probe !in 200..299) error("GoPro-ს Wi-Fi API მიუწვდომელია (HTTP $probe). ტელეფონი დაუკავშირე კამერის Wi-Fi-ს, არა სახლის როუტერს.")

                val udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(STREAM_PORT))
                    soTimeout = 800
                }
                socket = udp
                wifi.bindSocket(udp)

                // Stop any stale stream first, then request a fresh preview.
                runCatching { httpGet(wifi, "/gopro/camera/stream/stop", 2000) }
                val code = httpGet(wifi, "/gopro/camera/stream/start", 4000)
                if (code !in 200..299 && code != 409) {
                    error("GoPro-მ Preview უარყო (HTTP $code). შესაძლებელია ამ firmware-ს SD-ის გარეშე ნაკადი არ ჰქონდეს.")
                }
                sendStatus(state, "ველოდები GoPro-ს ვიდეონაკადს (UDP 8554)…")
                val temp = File(context.filesDir, "MOMENTO_preview_${System.currentTimeMillis()}.ts")
                file = temp
                val packetData = ByteArray(65_536)
                val pkt = DatagramPacket(packetData, packetData.size)
                var firstAt = -1L
                var bytes = 0L
                var lastReceivedAt = SystemClock.elapsedRealtime()
                var lastKeepAlive = 0L
                val maxWait = SystemClock.elapsedRealtime() + 10000L
                val plannedMs = (seconds * 1000.0).toLong().coerceAtLeast(1000L)
                temp.outputStream().buffered(256 * 1024).use { output ->
                    while (!cancelled.get()) {
                        val now = SystemClock.elapsedRealtime()
                        if (firstAt < 0 && now >= maxWait) error("GoPro-დან ვიდეონაკადი 10 წამში არ მოვიდა. SD-ის გარეშე Preview შესაძლოა დაბლოკილი იყოს.")
                        if (firstAt >= 0 && now - firstAt >= plannedMs) break
                        if (firstAt >= 0 && now - lastReceivedAt >= 3500) error("GoPro-ს ვიდეონაკადი შეწყდა ჩაწერის დროს")
                        if (now - lastKeepAlive >= 2200) {
                            lastKeepAlive = now
                            runCatching { keepAlive(udp) }
                        }
                        pkt.length = packetData.size
                        try { udp.receive(pkt) } catch (e: java.net.SocketTimeoutException) { continue }
                        catch (e: java.net.SocketException) { if (cancelled.get()) break else throw e }
                        val length = pkt.length
                        // MPEG transport packets begin with sync byte 0x47.
                        // Some firmwares prefix UDP datagrams; drop non-TS datagrams rather than
                        // polluting the source file with arbitrary bytes.
                        val tsPayload = MpegTsPackets.findPayload(packetData, length) ?: continue
                        if (firstAt < 0) {
                            firstAt = SystemClock.elapsedRealtime()
                            didStart = true
                            main.post { started() }
                            sendStatus(state, "GoPro-ს Preview ტელეფონში იწერება (30fps, არა 240fps)")
                        }
                        if (tsPayload.length > 0) {
                            output.write(packetData, tsPayload.offset, tsPayload.length)
                            bytes += tsPayload.length
                            lastReceivedAt = SystemClock.elapsedRealtime()
                        }
                    }
                }
                if (!didStart || bytes < 65536) error("ვიდეონაკადი არასაკმარისია ($bytes ბაიტი). ფაილი არ შეიქმნა.")
                // Only call finished(success) when actual TS payload was received.
                sendStatus(state, "Preview ჩაიწერა ტელეფონში: ${bytes / 1024} KB. იწყება დამუშავება…")
                main.post { finished(Result.success(temp)) }
            } catch (e: Exception) {
                file?.delete()
                val msg = if (cancelled.get() && !didStart) "გადაღება გაუქმდა" else (e.message ?: "უცნობი შეცდომა")
                sendStatus(state, "SD-ის გარეშე ჩაწერა ვერ შესრულდა: $msg")
                main.post { finished(Result.failure(IllegalStateException(msg, e))) }
            } finally {
                socket?.close()
                socket = null
                network?.let { runCatching { httpGet(it, "/gopro/camera/stream/stop", 1500) } }
                active = false
            }
        }
    }

    fun stop() { cancelled.set(true); socket?.close() }

    private fun sendStatus(cb: (String) -> Unit, msg: String) = main.post { cb(msg) }

    private fun httpGet(network: Network, path: String, timeout: Int): Int {
        val conn = network.openConnection(URL("http://$GOPRO_IP:8080$path")) as HttpURLConnection
        return try {
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.requestMethod = "GET"
            val code = conn.responseCode
            runCatching { conn.inputStream.close() }
            code
        } finally { conn.disconnect() }
    }

    private fun keepAlive(udp: DatagramSocket) {
        val msg = "_GPHD_:0:0:2:0.000000\n".toByteArray(Charsets.US_ASCII)
        udp.send(DatagramPacket(msg, msg.size, java.net.InetAddress.getByName(GOPRO_IP), STREAM_PORT))
    }
}
