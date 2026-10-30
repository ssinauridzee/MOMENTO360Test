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
                Pair(reply.optBoolean("drive_ready", false), reply.optBoolean("sms_configured", false))
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

    /** Upload directly to Google Drive, no WP disk usage. */
    fun upload(file: File, session: Session, progress: (String) -> Unit, done: (Result<String>) -> Unit) {
        Thread {
            val result=runCatching {
                val total=file.length()
                require(file.isFile && total in 16..536870912L){"MP4 უნდა იყოს 512 MB-ზე ნაკლები"}
                require(file.inputStream().use {
                    val head=ByteArray(12)
                    it.read(head)==12 && String(head,4,4,Charsets.US_ASCII)=="ftyp"
                }){"არასწორი MP4 ფაილი"}
                var restarted=false
                var id=""
                while(id.isBlank()){
                    val init=request("POST","/drive/start/${session.token}",
                        JSONObject().put("total",total).toString().toByteArray(Charsets.UTF_8),
                        uploadHeader(session)+mapOf("Content-Type" to "application/json"))
                    val url=init.getString("upload_url")
                    val parsed=URL(url)
                    require(parsed.protocol=="https" &&
                        parsed.host in listOf("www.googleapis.com","content.googleapis.com") &&
                        parsed.path.startsWith("/upload/drive/v3/files")){"არასწორი Drive upload URL"}
                    try{id=driveSend(file,url,total,progress)}
                    catch(e:DriveExpired){
                        if(restarted)throw e
                        restarted=true
                        request("POST","/drive/reset/${session.token}","{}".toByteArray(),
                            uploadHeader(session)+mapOf("Content-Type" to "application/json"))
                    }
                }
                val answer=request("POST","/drive/complete/${session.token}",
                    JSONObject().put("file_id",id).toString().toByteArray(),
                    uploadHeader(session)+mapOf("Content-Type" to "application/json"))
                check(answer.optBoolean("ready")){"Drive ფაილის დადასტურება ვერ მოხერხდა"}
                session.url
            }
            main.post{done(result)}
        }.start()
    }
    private class DriveExpired:Exception()
    private fun driveOffset(range:String?):Long {
        val m=Regex("bytes=0-([0-9]+)").matchEntire(range?.trim()?:"")
        return if(m==null)0L else (m.groupValues[1].toLongOrNull()?:-1L)+1
    }
    private fun statusDrive(url:String,total:Long):Pair<Long,String> {
        val c=webConnection(URL(url))
        try {
            c.requestMethod="PUT";c.connectTimeout=15000;c.readTimeout=25000
            c.instanceFollowRedirects=false
            c.setRequestProperty("Content-Range","bytes */${total}")
            c.doOutput=true;c.setFixedLengthStreamingMode(0)
            c.outputStream.use{}
            when(c.responseCode){
                308->return Pair(driveOffset(c.getHeaderField("Range")),"")
                200,201->return Pair(total,JSONObject(c.inputStream.bufferedReader().use{it.readText()}).getString("id"))
                404,410->throw DriveExpired()
                else->error("Drive status HTTP ${c.responseCode}")
            }
        } finally{c.disconnect()}
    }
    private fun driveSend(file:File,url:String,total:Long,progress:(String)->Unit):String {
        var (offset,existing)=statusDrive(url,total)
        if(existing.isNotEmpty())return existing
        var failed=0
        FileInputStream(file).use{input->
            while(offset<total){
                try{
                    input.channel.position(offset)
                    val n=minOf(1048576L,total-offset).toInt()
                    val bytes=ByteArray(n)
                    var got=0
                    while(got<n){
                        val part=input.read(bytes,got,n-got)
                        check(part>0){"MP4-ის კითხვა შეწყდა"}
                        got+=part
                    }
                    val c=webConnection(URL(url))
                    try{
                        c.requestMethod="PUT";c.connectTimeout=18000;c.readTimeout=45000
                        c.instanceFollowRedirects=false
                        c.setRequestProperty("Content-Type","video/mp4")
                        c.setRequestProperty("Content-Range","bytes ${offset}-${offset+n-1}/${total}")
                        c.doOutput=true;c.setFixedLengthStreamingMode(n)
                        c.outputStream.use{it.write(bytes)}
                        when(c.responseCode){
                            308->{val next=driveOffset(c.getHeaderField("Range"))
                                if(next<=offset){
                                    val state=statusDrive(url,total)
                                    if(state.second.isNotEmpty())return state.second
                                    offset=state.first
                                }else offset=next
                                failed=0
                            }
                            200,201->return JSONObject(c.inputStream.bufferedReader().use{it.readText()}).getString("id")
                            404,410->throw DriveExpired()
                            else->error("Drive HTTP ${c.responseCode}: "+
                                (c.errorStream?.bufferedReader()?.use{it.readText()}?:"").take(150))
                        }
                    }finally{c.disconnect()}
                    main.post{progress("Google Drive: ${offset*100/total}%")}
                }catch(e:DriveExpired){throw e}
                catch(e:Exception){
                    if(++failed>=5)throw e
                    Thread.sleep(1200L*failed)
                    val state=statusDrive(url,total)
                    if(state.second.isNotEmpty())return state.second
                    offset=state.first
                }
            }
        }
        val final=statusDrive(url,total)
        if(final.second.isNotEmpty())return final.second
        error("Drive-ს ფაილის ID არ დაუბრუნებია")
    }
}
