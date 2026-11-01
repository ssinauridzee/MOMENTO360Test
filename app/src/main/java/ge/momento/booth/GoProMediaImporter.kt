package ge.momento.booth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** HERO9 original microSD MP4 transfer via GoPro Wi-Fi (not the 30 FPS Preview). */
class GoProMediaImporter(private val context: Context) {
    data class Credentials(val ssid: String, val password: String)
    private data class Media(val dir: String, val name: String, val size: Long, val mod: Long) {
        val fingerprint get() = "$dir/$name/$size/$mod"
        var proxy: Media? = null
    }
    private val handler=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs=context.getSharedPreferences("momento_gopro_wifi", Context.MODE_PRIVATE)
    private val cancelled=AtomicBoolean(false)
    private val finishOnce=AtomicBoolean(false)
    @Volatile private var busy=false
    private var callback:ConnectivityManager.NetworkCallback?=null
    /** Fast mode downloads HERO9's much smaller LRV proxy (reduced visual quality). */
    fun isFastMode():Boolean = prefs.getBoolean("fast_lrv",true)
    fun setFastMode(enabled:Boolean) {
        prefs.edit().putBoolean("fast_lrv",enabled).apply()
    }
    /** Mark deduplicated ONLY after CaptureQueue safely persisted the file. */
    @Synchronized fun markCommitted(file:File) {
        val fingerprint=prefs.getString("pending_fingerprint_"+file.name,null) ?: return
        recordDelivered(fingerprint)
        prefs.edit().remove("pending_fingerprint_"+file.name).commit()
    }

    fun isBusy()=busy

    /** Interrupt a background transfer immediately when the operator presses START.
     * The .partial file is retained and next attempt uses HTTP Range to resume it. */
    fun pause() { cancelled.set(true) }

    private fun delivered():Set<String> {
        val array=runCatching{JSONArray(prefs.getString("delivered_v15","[]"))}.getOrElse{JSONArray()}
        return (0 until array.length()).mapNotNull{array.optString(it).takeIf{value->value.isNotBlank()}}.toSet()
    }
    private fun recordDelivered(fingerprint:String) {
        val list=(delivered().toList()+fingerprint).distinct().takeLast(200)
        val array=JSONArray()
        list.forEach{array.put(it)}
        prefs.edit().putString("delivered_v15",array.toString()).putString("last_downloaded",fingerprint).commit()
    }
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
        if(!finishOnce.compareAndSet(false,true))return
        val old=callback;callback=null
        if(old!=null)runCatching{manager.unregisterNetworkCallback(old)}
        busy=false
        handler.post{cb(result)}
    }
    fun downloadLatest(startedAt:Long,progress:(String)->Unit,done:(Result<File>)->Unit,sequential:Boolean=false,pendingCount:Int=1){
        if(busy){done(Result.failure(IllegalStateException("უკვე მიმდინარეობს GoPro-დან გადმოტანა")));return}
        busy=true;cancelled.set(false);finishOnce.set(false)
        status(progress,"GoPro Wi-Fi-ის ძებნა… თუ Android ქსელთან დაკავშირებას გთხოვს, დააჭირე Connect")
        thread(name="momento-wifi-select"){
            status(progress,"ვამოწმებ, უკვე დაკავშირებულია თუ არა ტელეფონი GoPro-ს Wi-Fi-ზე…")
            val wifi=manager.allNetworks.firstOrNull{net->
                manager.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true &&
                runCatching{listMedia(net).isNotEmpty()}.getOrDefault(false)
            }
            if(cancelled.get()){
                finish(done,Result.failure(InterruptedException("ახალი გადაღების გამო გადმოტანა შეჩერდა")));return@thread
            }
            if(wifi!=null){
                status(progress,"GoPro-ს Wi-Fi უკვე დაკავშირებულია — ვკითხულობ ვიდეოების სიას…")
                doDownload(wifi,startedAt,progress,done,sequential,pendingCount);return@thread
            }
            status(progress,"ტელეფონი GoPro-ს Wi-Fi-ზე არ არის — Android-ს ვთხოვ კამერის ქსელზე გადასვლას…")
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
                if(cancelled.get()){
                    finish(done,Result.failure(InterruptedException("ჩამოტვირთვა შეჩერებულია")))
                    return@post
                }
                try{
                    val spec=WifiNetworkSpecifier.Builder().setSsid(credentials.ssid)
                        .setWpa2Passphrase(credentials.password).build()
                    val request=NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(spec).build()
                    val cb=object:ConnectivityManager.NetworkCallback(){
                        override fun onAvailable(network:Network){
                            status(progress,"Wi-Fi დამყარდა; ველოდებით GoPro-ს ფაილების სერვერის მზადყოფნას…")
                            if(cancelled.get())
                                finish(done,Result.failure(InterruptedException("გადმოტანა ახალი გადაღებისთვის შეჩერდა")))
                            else doDownload(network,startedAt,progress,done,sequential,pendingCount)
                        }
                        override fun onUnavailable(){
                            finish(done,Result.failure(IllegalStateException(
                                "Android-მა GoPro Wi-Fi კავშირი ვერ დაამყარა. გადაამოწმე პაროლი და დაადასტურე ქსელის მოთხოვნა.")))
                        }
                    }
                    callback=cb
                    status(progress,"ახლა Android-ის ფანჯარაში დაადასტურე GoPro Wi-Fi (Connect). უსასრულოდ არ დაელოდო — 30 წამში გამოჩნდება შეცდომა.")
                    manager.requestNetwork(request,cb,30000)
                }catch(e:Exception){finish(done,Result.failure(e))}
            }
        }
    }
    private fun doDownload(network:Network,startedAt:Long,progress:(String)->Unit,done:(Result<File>)->Unit,sequential:Boolean,pendingCount:Int){
        thread(name="momento-gopro-mp4"){
            val result=runCatching{
                var problem:Exception?=null
                var chosen:Media?=null
                val alreadyDelivered=delivered()+listOfNotNull(prefs.getString("last_downloaded",""))
                for(attempt in 1..18){
                    if(cancelled.get())throw InterruptedException("ახალი გადაღების გამო ჩამოტვირთვა შეჩერებულია")
                    if(attempt==1 || attempt%3==0)
                        status(progress,"GoPro-ს ვიდეოების რიგის შემოწმება $attempt/18…")
                    try {
                        val items=listMedia(network)
                        // HERO9 modified timestamps are NOT guaranteed to match the
                        // phone clock (drift, timezone, wrong camera clock). Do not
                        // reject all clips based on an arbitrary 30-second cutoff.
                        val remaining=items.filter { it.fingerprint !in alreadyDelivered }
                            .sortedWith(compareBy<Media>{it.mod}.thenBy{it.dir}.thenBy{it.name})
                        // The newest N not-yet-delivered short clips belong to N
                        // queued captures; deliver those oldest-first. This avoids
                        // accidentally selecting an ancient video from microSD.
                        // Operators should verify the newest N files when recovering
                        // a historic queue after external captures.
                        chosen=if(sequential) {
                            remaining.takeLast(pendingCount.coerceIn(1,300)).firstOrNull()
                        } else {
                            remaining.lastOrNull()
                        }
                        if(attempt==1)status(progress,
                            "GoPro-ზე MP4: ${items.size}, უკვე გადმოტანილი: ${items.size-remaining.size}, " +
                            "ჩვენი რიგი: ${pendingCount}. " +
                            (chosen?.let{"არჩეული: ${it.name}"} ?: "მოსაძებნია ახალი MP4"))
                        if(chosen!=null)break
                    } catch(e:Exception){problem=e}
                    Thread.sleep(1300L)
                }
                val selected=chosen ?: throw (problem ?: IllegalStateException(
                    "ამ გადაღების ახალი MP4 კამერაზე ვერ მოიძებნა; ვიდეო ისევ GoPro-ს microSD-ზეა. კამერის თარიღი გადაამოწმე."))
                if(!sequential && prefs.getString("last_downloaded","")==selected.fingerprint) {
                    val cached=File(context.filesDir,"MOMENTO_GOPRO_"+selected.dir+"_"+selected.name)
                    if(cached.isFile && cached.length()>1024)return@runCatching cached
                    error("ეს ვიდეო უკვე გადმოტანილია")
                }
                if(selected.size>512L*1024*1024)error("MP4 512 MB-ზე დიდია")
                if(cancelled.get())throw InterruptedException("ახალი გადაღების გამო ჩამოტვირთვა შეჩერებულია")
                status(progress,"ჩანაწერი "+selected.name+" მოიძებნა — ვიწერთ…")
                var file:File?=null
                if(isFastMode()){
                    val originalStem=selected.name.substringBeforeLast(".")
                    val fallbackName="GL"+originalStem.drop(2)+".LRV"
                    val proxy=selected.proxy ?: Media(selected.dir,fallbackName,0L,selected.mod)
                    status(progress,"⚡ სწრაფი LRV რეჟიმი: ${proxy.name}")
                    file=runCatching{download(network,selected,proxy,progress,true)}
                        .onFailure{error->
                            if(cancelled.get())throw InterruptedException("სწრაფი გადმოტანა შეჩერებულია")
                            status(progress,"LRV მიუწვდომელია (${error.message?.take(80)}). ვცდით სრულ MP4-ს…")
                        }.getOrNull()
                }
                if(file==null)file=download(network,selected,selected,progress,false)
                if(cancelled.get())throw InterruptedException("ახალი გადაღების გამო ჩამოტვირთვა შეჩერებულია")
                check(prefs.edit().putString("pending_fingerprint_"+file.name,selected.fingerprint).commit()){
                    "ვიდეოს ID ვერ შევინახეთ"
                }
                status(progress,"GoPro ვიდეო ტელეფონშია (${file.length()/1024/1024} MB). იწყება Slow Motion…")
                file
            }
            finish(done,result)
        }
    }
    private fun connection(net:Network,path:String):HttpURLConnection =
        net.openConnection(URL("http://10.5.5.9:8080"+path)) as HttpURLConnection

    private fun listMedia(net:Network):List<Media>{
        val c=connection(net,"/gopro/media/list")
        try {
            c.connectTimeout=5500;c.readTimeout=11000
            if(c.responseCode!=200)error("GoPro ფაილების სია HTTP "+c.responseCode)
            val root=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            val dirs=root.optJSONArray("media")?:error("GoPro microSD-ის სია მიუწვდომელია")
            val originals=ArrayList<Media>()
            val proxies=HashMap<String,Media>()
            for(i in 0 until dirs.length()){
                val folder=dirs.optJSONObject(i)?:continue
                val dir=folder.optString("d")
                if(!dir.matches(Regex("[A-Za-z0-9_-]{1,32}")))continue
                val files=folder.optJSONArray("fs")?:continue
                for(j in 0 until files.length()){
                    val item=files.optJSONObject(j)?:continue
                    val filename=item.optString("n")
                    if(!filename.matches(Regex("[A-Za-z0-9_.-]{4,96}")))continue
                    val media=Media(dir,filename,
                        item.optString("s","0").toLongOrNull()?:0,
                        item.optString("mod","0").toLongOrNull()?:0)
                    if(filename.endsWith(".mp4",true))originals.add(media)
                    else if(filename.endsWith(".lrv",true)) {
                        val id=filename.drop(2).substringBeforeLast('.')
                        proxies["$dir/$id"]=media
                    }
                }
            }
            originals.forEach { video ->
                val id=video.name.drop(2).substringBeforeLast('.')
                video.proxy=proxies["${video.dir}/$id"]
            }
            return originals
        }finally{c.disconnect()}
    }

    private fun isValidMp4(file:File):Boolean=runCatching{
        file.isFile && file.length()>1024 &&
            file.inputStream().use { input ->
                val b=ByteArray(12)
                input.read(b)==12 &&
                    String(b,4,4,Charsets.US_ASCII)=="ftyp"
            }
    }.getOrDefault(false)

    /** Uses Range to resume interrupted transfers. Always keep the original
     * camera file untouched on microSD; writes private .partial phone file. */
    private fun download(net:Network,origin:Media,transfer:Media,
                         progress:(String)->Unit,fast:Boolean):File {
        val stem=origin.name.substringBeforeLast('.')
        val suffix=if(fast)"_FAST" else "_FULL"
        val filename="MOMENTO_GOPRO_${origin.dir}_${stem}${suffix}.mp4"
        val destination=File(context.filesDir,filename)
        val partial=File(context.filesDir,filename+".partial")
        if(isValidMp4(destination) &&
            (transfer.size<=0 || destination.length()==transfer.size)) {
            status(progress,"შენახული ვიდეო გამოყენებულია: ${destination.name}")
            return destination
        }
        var count=if(partial.exists())partial.length()else 0L
        if(transfer.size>0 && count>transfer.size){partial.delete();count=0L}
        var failures=0
        var previousPercent=-1L
        while(true) {
            if(cancelled.get())throw InterruptedException("ახალი გადაღებისთვის ჩამოტვირთვა შეჩერდა")
            val c=connection(net,"/videos/DCIM/${transfer.dir}/${transfer.name}")
            try {
                c.connectTimeout=8000;c.readTimeout=12000
                if(count>0)c.setRequestProperty("Range","bytes=$count-")
                val http=c.responseCode
                if(http!=200 && http!=206)
                    error("GoPro ${transfer.name}: HTTP $http")
                if(count>0 && http==200){
                    count=0L;partial.delete() // Server did not support resume.
                }
                if(count>0 && http==206){
                    val header=c.getHeaderField("Content-Range")?:""
                    if(!header.startsWith("bytes $count-")) {
                        count=0L;partial.delete()
                        throw java.io.IOException("GoPro Range პასუხი არასწორია")
                    }
                }
                val reportedTotal=if(transfer.size>0)transfer.size else
                    c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: c.contentLengthLong.let{ if(it>0)it+count else 0L }
                if(reportedTotal>512L*1024*1024)
                    error("GoPro ფაილი 512 MB-ზე დიდია; გამოიყენე სწრაფი რეჟიმი")
                FileOutputStream(partial,count>0).buffered(256*1024).use{output->
                    c.inputStream.use{input->
                        val bytes=ByteArray(256*1024)
                        while(true) {
                            if(cancelled.get())throw InterruptedException("ახალი გადაღებისთვის გადმოტანა შეჩერდა")
                            val got=input.read(bytes)
                            if(got<0)break
                            output.write(bytes,0,got)
                            count+=got
                            if(count>512L*1024*1024)error("GoPro ფაილი მეტისმეტად დიდია")
                            val pct=if(reportedTotal>0)count*100/reportedTotal else 0L
                            if(reportedTotal>0 && pct/5!=previousPercent/5) {
                                previousPercent=pct
                                status(progress,"${if(fast)"⚡ სწრაფი LRV" else "🎥 სრული MP4"}: $pct%")
                            }
                        }
                    }
                }
                if(reportedTotal>0 && count!=reportedTotal)
                    error("GoPro ვიდეო არასრულია: $count/$reportedTotal")
                if(!isValidMp4(partial))error("მიღებული ფაილი გამართული MP4 არ არის")
                if(destination.exists() && !destination.delete())
                    error("ძველი არასრული MP4 ვერ წაიშალა")
                check(partial.renameTo(destination)){"GoPro ვიდეო ვერ შეინახა"}
                return destination
            }catch(e:Exception) {
                if(cancelled.get() || e is InterruptedException || ++failures>=3)throw e
                count=partial.length()
                Thread.sleep(failures*950L)
            }finally{c.disconnect()}
        }
    }

    /** On manual downloads, publish a visible copy to Movies/MOMENTO360.
     * Automatic sessions keep their working files private to avoid filling the phone gallery with 100s of duplicates. */
    private fun publishToGallery(file:File,media:Media,progress:(String)->Unit){
        if(Build.VERSION.SDK_INT<29) {
            status(progress,"GoPro MP4 ჩამოტანილია აპლიკაციაში; Android 9-ზე საჯარო Movies ექსპორტი არ არის ჩართული")
            return
        }
        if(prefs.getString("gallery_copy","")==media.fingerprint)return
        var uri:android.net.Uri?=null
        try{
            status(progress,"ვინახავთ MP4 ასლს ტელეფონის Movies/MOMENTO360 საქაღალდეში…")
            val resolver=context.contentResolver
            val values=ContentValues().apply{
                put(MediaStore.Video.Media.DISPLAY_NAME,"MOMENTO_"+media.dir+"_"+media.name)
                put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/MOMENTO360")
                put(MediaStore.Video.Media.IS_PENDING,1)
            }
            uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
                ?:error("Movies საქაღალდის გახსნა ვერ მოხერხდა")
            resolver.openOutputStream(uri)?.use{out->
                file.inputStream().buffered().use{input->input.copyTo(out)}
            } ?: error("Gallery MP4 ფაილის ჩაწერა ვერ მოხერხდა")
            resolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
            prefs.edit().putString("gallery_copy",media.fingerprint).apply()
            status(progress,"MP4 ტელეფონშიც შენახულია: Movies/MOMENTO360")
        }catch(e:Exception){
            uri?.let{runCatching{context.contentResolver.delete(it,null,null)}}
            status(progress,"MP4 აპლიკაციაში გადმოტანილია; Movies-ში დამატება ვერ მოხერხდა: "+e.message)
        }
    }

    fun close(){
        cancelled.set(true)
        val old=callback;callback=null
        if(old!=null)runCatching{manager.unregisterNetworkCallback(old)}
    }
}
