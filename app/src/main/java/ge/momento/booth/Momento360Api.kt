package ge.momento.booth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/** MOMENTO 360 WordPress REST client. Network work never runs on the UI thread. */
class Momento360Api(private val context: Context) {
    data class Session(val token: String, val uploadKey: String, val url: String)
    private val main = Handler(Looper.getMainLooper())
    private val base = "https://momento.ge/wp-json/momento/v1/360"
    private val prefs = context.getSharedPreferences("m360_operator", Context.MODE_PRIVATE)

    fun getSession(): Session? = runCatching {
        val token = prefs.getString("session_token", "") ?: ""
        val key = prefs.getString("upload_key", "") ?: ""
        val url = prefs.getString("video_url", "") ?: ""
        if (token.isBlank() || key.isBlank() || url.isBlank()) null else Session(token, key, url)
    }.getOrNull()
    fun saveSession(s: Session) {
        prefs.edit().putString("session_token", s.token).putString("upload_key", s.uploadKey)
            .putString("video_url", s.url).apply()
    }
    fun clearSession() { prefs.edit().remove("session_token").remove("upload_key").remove("video_url").apply() }
    fun getExportedFile(): File? {
        val path = prefs.getString("output_path", "") ?: ""
        if (path.isEmpty()) return null
        val f = File(path)
        return f.takeIf { it.isFile && it.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator) }
    }
    fun saveExportedFile(file: File) { prefs.edit().putString("output_path", file.absolutePath).apply() }
    fun clearExportedFile() { prefs.edit().remove("output_path").apply() }

    fun hasKey() = !prefs.getString("app_key", "").isNullOrBlank()
    fun saveKey(key: String) { prefs.edit().putString("app_key", key.trim()).apply() }

    // HERO9 AP is local-only Wi-Fi. Route momento.ge to mobile data when available;
    // otherwise Android may try the internet-less GoPro network and fail.
    private fun webConnection(url: URL): HttpURLConnection {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val mobile = cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return (mobile?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
    }

    private fun request(method: String, path: String, bytes: ByteArray? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val connection = webConnection(URL(base + path))
        connection.requestMethod = method
        connection.connectTimeout = 18000
        connection.readTimeout = 35000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
        try {
            if (bytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val result = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("message", text) }
            if (responseCode !in 200..299) throw IllegalStateException(result.optString("message", "HTTP $responseCode"))
            return result
        } finally { connection.disconnect() }
    }

    private fun operatorHeader(): Map<String,String> = mapOf("X-Momento-360-Key" to (prefs.getString("app_key", "") ?: ""))
    private fun uploadHeader(s: Session) = mapOf("X-Momento-Upload-Key" to s.uploadKey)

    /** Check WordPress integration, server-side private storage and SMS configuration. */
    fun checkServer(done: (Result<Pair<Boolean, Boolean>>) -> Unit) {
        Thread {
            val r = runCatching {
                require(hasKey()) { "ჯერ შეინახე WordPress-ის 360 API გასაღები." }
                val reply = request("GET", "/check", null, operatorHeader())
                Pair(reply.optBoolean("storage_ready", false), reply.optBoolean("sms_configured", false))
            }
            main.post { done(r) }
        }.start()
    }

    fun reserve(done: (Result<Session>) -> Unit) {
        Thread {
            val r = runCatching {
                require(hasKey()) { "შეიყვანე საიტის 360 API გასაღები." }
                val body = JSONObject().put("event", "MOMENTO 360").toString().toByteArray(Charsets.UTF_8)
                val reply = request("POST", "/reserve", body, operatorHeader() + mapOf("Content-Type" to "application/json"))
                Session(reply.getString("token"), reply.getString("upload_key"), reply.getString("url"))
            }
            main.post { done(r) }
        }.start()
    }

    fun sendSms(session: Session, name: String, phone: String, done: (Result<Boolean>) -> Unit) {
        Thread {
            val r = runCatching {
                val body = JSONObject().put("token", session.token).put("name", name).put("phone", phone)
                val reply = request("POST", "/send-sms", body.toString().toByteArray(Charsets.UTF_8),
                    operatorHeader() + mapOf("Content-Type" to "application/json"))
                reply.optBoolean("sms_accepted", false)
            }
            main.post { done(r) }
        }.start()
    }

    /** Sequential 1 MiB chunks, resuming at the server's persisted offset when retried. */
    fun upload(file: File, session: Session, progress: (String) -> Unit, done: (Result<String>) -> Unit) {
        Thread {
            val r = runCatching {
                val total = file.length()
                require(total in 16..536870912L) { "MP4 უნდა იყოს 512 MiB-ზე მცირე." }
                var offset = request("GET", "/upload-status/${session.token}", null, uploadHeader(session))
                    .getLong("uploaded_bytes")
                if (offset > total) error("სერვერზე ატვირთული ზომა ლოკალურ ვიდეოს აღემატება.")
                FileInputStream(file).use { input ->
                    input.channel.position(offset)
                    var failures = 0
                    while (offset < total) {
                        val batch = ByteArray(minOf(1_048_576L, total - offset).toInt())
                        var received = 0
                        while (received < batch.size) {
                            val n = input.read(batch, received, batch.size - received)
                            if (n == -1) error("ვიდეოს კითხვა მოულოდნელად შეწყდა")
                            received += n
                        }
                        try {
                            val reply = request("POST", "/chunk/${session.token}?offset=$offset&total=$total", batch,
                                uploadHeader(session) + mapOf("Content-Type" to "application/octet-stream"))
                            offset = reply.getLong("uploaded_bytes")
                            failures = 0
                            val percentage = (offset * 100 / total).toInt()
                            main.post { progress("ვიდეო იტვირთება: $percentage%") }
                        } catch (e: Exception) {
                            if (++failures > 3) throw e
                            Thread.sleep(1500L * failures)
                            val status = request("GET", "/upload-status/${session.token}", null, uploadHeader(session))
                            offset = status.getLong("uploaded_bytes")
                            input.channel.position(offset)
                        }
                    }
                }
                val completed = request("POST", "/complete/${session.token}", "{}".toByteArray(),
                    uploadHeader(session) + mapOf("Content-Type" to "application/json"))
                if (!completed.optBoolean("ready", false)) error("სერვერმა ატვირთვა ვერ დაასრულა")
                session.url
            }
            main.post { done(r) }
        }.start()
    }
}
