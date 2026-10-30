package ge.momento.booth

import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** HERO9 original microSD MP4 transfer via GoPro Wi-Fi (not the 30 FPS Preview). */
class GoProMediaImporter(private val context: Context) {
    data class Credentials(val ssid: String, val password: String)
    private data class Media(val dir: String, val name: String, val size: Long, val mod: Long) {
        val fingerprint get() = "$dir/$name/$size/$mod"
    }
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs=context.getSharedPreferences("momento_gopro_wifi", Context.MODE_PRIVATE)
    private val cancelled=AtomicBoolean(false)
    private val finished=AtomicBoolean(false)
    private val networkActivated=AtomicBoolean(false)
    @Volatile private var busy=false
    private var callback:ConnectivityManager.NetworkCallback?=null

    fun isBusy()=busy
    fun saved():Credentials? {
        val ssid=prefs.getString("ssid","")!!.trim()
        val password=prefs.getString("password","")!!
        return if(ssid.isNotEmpty()&&password.length in 8..63)Credentials(ssid,password)else null
    }
    fun save(ssid:String,password:String){
        require(ssid.isNotBlank()&&ssid.length<=32){"Wi-Fi SSID მიუთითე"}
        require(password.length in 8..63){"GoPro Wi-Fi პაროლი 8–63 სიმბოლო უნდა იყოს"}
        prefs.edit().putString("ssid",ssid.trim()).putString("password",password).apply()
    }
    private fun status(out:(String)->Unit,message:String){handler.post{out(message)}}
    private fun finish(cb:(Result<File>)->Unit,result:Result<File>){
        if (!finished.compareAndSet(false,true)) return
        val old=callback;callback=null
        if(old!=null)runCatching{manager.unregisterNetworkCallback(old)}
        busy=false
        handler.post{cb(result)}
    }
    fun downloadLatest(startedAt:Long,progress:(String)->Unit,done:(Result<File>)->Unit){
        if(busy){done(Result.failure(IllegalStateException("უკვე მიმდინარეობს GoPro-დან გადმოტანა")));return}
        busy=true;cancelled.set(false);finished.set(false);networkActivated.set(false)
        status(progress,"GoPro Wi-Fi-ის ძებნა…")
        thread(name="momento-wifi-select"){
            val wifi=manager.allNetworks.firstOrNull{net->
                manager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true &&
                runCatching{listMedia(net).isNotEmpty()}.getOrDefault(false)
            }
            if(wifi!=null){doDownload(wifi,startedAt,progress,done);return@thread}
            val credentials=saved()
            if(credentials==null){
                finish(done,Result.failure(IllegalStateException(
                    "GoPro Wi-Fi მონაცემები ჯერ არ არის შენახული. გახსენი GoPro → Wi-Fi მონაცემები")))
                return@thread
            }
            if(Build.VERSION.SDK_INT<29){
                finish(done,Result.failure(IllegalStateException("GoPro-ს Wi-Fi დაუკავშირე Android-ის პარამეტრებიდან")))
                return@thread
            }
            handler.post {
                try{
                    val spec=WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
                        .setWpa2Passphrase(credentials.password).build()
                    val request=NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(spec).build()
                    val cb=object:ConnectivityManager.NetworkCallback(){
                        override fun onAvailable(network:Network){
                            if (!networkActivated.compareAndSet(false,true)) return
                            status(progress,"GoPro Wi-Fi დაკავშირებულია • ვამოწმებთ კამერის ფაილებს")
                            doDownload(network,startedAt,progress,done)
                        }
                        override fun onUnavailable(){
                            if (!networkActivated.get()) finish(done,Result.failure(IllegalStateException(
                                "Android-მა GoPro Wi-Fi ვერ დააკავშირა. GoPro-ზე ჩართე Wi-Fi, გადაამოწმე SSID/პაროლი და Android-ის კავშირის ფანჯარაში დააჭირე Connect.")))
                        }
                    }
                    callback=cb
                    status(progress,"დაადასტურე GoPro Wi-Fi კავშირი Android-ის ფანჯარაში")
                    manager.requestNetwork(request,cb,30000)
                }catch(e:Exception){finish(done,Result.failure(e))}
            }
        }
    }
    private fun doDownload(network:Network,startedAt:Long,progress:(String)->Unit,done:(Result<File>)->Unit){
        thread(name="momento-gopro-mp4"){
            val result=runCatching{
                var items:List<Media> = emptyList()
                var problem:Exception?=null
                for(attempt in 1..9){
                    if(cancelled.get())error("გადმოტანა შეჩერებულია")
                    try {
                        items=listMedia(network)
                        problem=null
                        // Video metadata can lag behind the shutter STOP acknowledgement.
                        val current=items.maxWithOrNull(compareBy<Media>{it.mod}.thenBy{it.name})
                        val oldForThisSession=startedAt>0 && current!=null && current.mod>0 &&
                            current.mod*1000L < startedAt-300000L
                        if(items.isNotEmpty()&&!oldForThisSession)break
                    }catch(e:Exception){problem=e}
                    Thread.sleep(1200L)
                }
                if(items.isEmpty())throw problem?:IllegalStateException("GoPro-ზე MP4 არ მოიძებნა")
                val latest=items.maxWithOrNull(compareBy<Media>{it.mod}.thenBy{it.name})
                    ?:error("GoPro-ის ვიდეო ვერ მოიძებნა")
                if(prefs.getString("last_downloaded","")==latest.fingerprint){
                    // Manual retry can reuse the original local MP4 if export or upload failed.
                    val cached=File(context.filesDir,"MOMENTO_GOPRO_"+latest.dir+"_"+latest.name)
                    if(startedAt==0L && cached.isFile && cached.length()>1024) {
                        publishIfNeeded(cached,latest,progress)
                        return@runCatching cached
                    }
                    error("ეს GoPro ვიდეო უკვე გადმოტანილია — ახალი ჩანაწერი ვერ მოიძებნა")
                }
                if(startedAt>0 && latest.mod>0 && latest.mod*1000L < startedAt-300000L)
                    error("GoPro-ის ბოლო ვიდეო ძველია. კამერის თარიღი/საათი გადაამოწმე.")
                if(latest.size>512L*1024*1024)error("MP4 512 MB-ზე დიდია — ამ ვერსიას პატარა ჩანაწერი სჭირდება")
                status(progress,"GoPro: "+latest.name+" — ჩამოტვირთვა")
                val file=download(network,latest,progress)
                prefs.edit().putString("last_downloaded",latest.fingerprint).apply()
                publishIfNeeded(file,latest,progress)
                file
            }
            finish(done,result)
        }
    }
    private fun connection(net:Network,path:String):HttpURLConnection =
        net.openConnection(URL("http://10.5.5.9:8080"+path)) as HttpURLConnection

    private fun listMedia(net:Network):List<Media>{
        val c=connection(net,"/gopro/media/list")
        try{
            c.connectTimeout=5500;c.readTimeout=10000
            if(c.responseCode!=200)error("GoPro API HTTP "+c.responseCode)
            val root=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val dirs=root.optJSONArray("media")?:error("GoPro მედია სია მიუწვდომელია")
            val result=ArrayList<Media>()
            for(i in 0 until dirs.length()){
                val folder=dirs.optJSONObject(i)?:continue
                val name=folder.optString("d")
                if(!name.matches(Regex("[A-Za-z0-9_-]{1,32}")))continue
                val fs=folder.optJSONArray("fs")?:continue
                for(j in 0 until fs.length()){
                    val item=fs.optJSONObject(j)?:continue
                    val file=item.optString("n")
                    if(!file.endsWith(".mp4",true)||!file.matches(Regex("[A-Za-z0-9_.-]{4,96}")))continue
                    result.add(Media(name,file,item.optString("s","0").toLongOrNull()?:0,
                        item.optString("mod","0").toLongOrNull()?:0))
                }
            }
            return result
        }finally{c.disconnect()}
    }
    private fun download(net:Network,video:Media,progress:(String)->Unit):File{
        val basename=video.dir+"_"+video.name
        val destination=File(context.filesDir,"MOMENTO_GOPRO_"+basename)
        val partial=File(context.filesDir,"MOMENTO_GOPRO_"+basename+".partial")
        var count=if(partial.exists())partial.length()else 0L
        if(count>video.size&&video.size>0){partial.delete();count=0}
        var failed=0
        while(true){
            if(cancelled.get())error("GoPro ჩამოტვირთვა შეწყდა")
            val c=connection(net,"/videos/DCIM/"+video.dir+"/"+video.name)
            try{
                c.connectTimeout=10000;c.readTimeout=25000
                if(count>0)c.setRequestProperty("Range","bytes=$count-")
                val http=c.responseCode
                if(http!=200&&http!=206)error("GoPro MP4 HTTP $http")
                if(count>0&&http==200){count=0;partial.delete()}
                FileOutputStream(partial,count>0).buffered(128*1024).use{output->
                    c.inputStream.use{input->
                        val buffer=ByteArray(131072)
                        while(true){
                            if(cancelled.get())error("გადმოტანა შეჩერებულია")
                            val n=input.read(buffer)
                            if(n<0)break
                            output.write(buffer,0,n);count+=n
                            if(count>512L*1024*1024)error("ვიდეო 512 MB-ზე დიდია")
                            if(video.size>0&&count%(1024*1024)<n)
                                status(progress,"GoPro-დან გადმოტანა: "+count*100/video.size+"%")
                        }
                    }
                }
                if(video.size>0&&count!=video.size)error("არასრული MP4: $count / "+video.size)
                if(partial.length()<1024)error("ცარიელი MP4")
                if(!partial.inputStream().use{val b=ByteArray(12);it.read(b)==12&&String(b,4,4,Charsets.US_ASCII)=="ftyp"})
                    error("GoPro ფაილი არ არის MP4")
                if(destination.exists())destination.delete()
                check(partial.renameTo(destination)){"ფაილის შენახვის შეცდომა"}
                return destination
            }catch(e:Exception){
                if(cancelled.get()||++failed>=5)throw e
                count=partial.length()
                Thread.sleep(failed*1200L)
            }finally{c.disconnect()}
        }
    }
    /**
     * Store a visible copy in Movies/MOMENTO 360, not only in the app-private folder.
     * Private copy stays available for video editing and resilient upload.
     */
    private fun publishIfNeeded(file:File, media:Media, progress:(String)->Unit) {
        if(Build.VERSION.SDK_INT<29){
            status(progress,"ვიდეო ჩამოტანილია აპლიკაციაში; Downloads-ში შენახვა Android 10+-ზე მუშაობს")
            return
        }
        if(prefs.getString("last_published","")==media.fingerprint)return
        var contentUri:android.net.Uri?=null
        try {
            status(progress,"ტელეფონში Movies/MOMENTO 360-ში MP4-ის შენახვა…")
            val values=ContentValues().apply{
                put(MediaStore.Video.Media.DISPLAY_NAME, "MOMENTO_"+media.name)
                put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES+"/MOMENTO 360")
                put(MediaStore.Video.Media.IS_PENDING,1)
            }
            val resolver=context.contentResolver
            contentUri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
                ?:error("ტელეფონის ვიდეოსაცავის შექმნა ვერ მოხერხდა")
            resolver.openOutputStream(contentUri,"w")?.use{output->
                file.inputStream().buffered().use{input->input.copyTo(output,131072)}
            }?:error("ვიდეოს საჯარო ფაილში ჩაწერა ვერ მოხერხდა")
            resolver.update(contentUri,ContentValues().apply{
                put(MediaStore.Video.Media.IS_PENDING,0)
            },null,null)
            prefs.edit().putString("last_published",media.fingerprint).apply()
            status(progress,"MP4 ტელეფონში შენახულია: Movies/MOMENTO 360")
        }catch(e:Exception){
            contentUri?.let{runCatching{context.contentResolver.delete(it,null,null)}}
            status(progress,"MP4 ჩამოტანილია, მაგრამ გალერეაში ასლი ვერ შეიქმნა: "+
                (e.message?:"საცავის შეცდომა"))
        }
    }
    fun close(){
        cancelled.set(true)
        val old=callback;callback=null
        if(old!=null)runCatching{manager.unregisterNetworkCallback(old)}
    }
}
