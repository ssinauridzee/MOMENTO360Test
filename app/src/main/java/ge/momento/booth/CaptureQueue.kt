package ge.momento.booth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FIFO of recordings that have already been stopped on the camera.
 * A file and its RecordingPlan stay bound to the original capture ID,
 * independent of newer START actions, Wi-Fi interruptions or app restarts.
 */
class CaptureQueue(private val context:Context) {
    data class Job(
        val id:Long,
        val plan:RecordingPlan,
        val rawName:String,
        val retryAfter:Long,
        val attempts:Int,
        val error:String
    ) {
        fun localRaw(dir:File):File? {
            if (!rawName.matches(Regex("MOMENTO_GOPRO_[A-Za-z0-9_.-]+\\.mp4"))) return null
            val file=File(dir,rawName)
            return file.takeIf { it.isFile && it.length()>1024 && it.canonicalFile.parentFile==dir.canonicalFile }
        }
    }
    private val prefs=context.getSharedPreferences("momento_capture_queue_v15",Context.MODE_PRIVATE)

    private fun rows():MutableList<JSONObject> {
        val data=runCatching { JSONArray(prefs.getString("captures","[]")) }.getOrElse { JSONArray() }
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.toMutableList()
    }
    private fun save(rows:List<JSONObject>) {
        val array=JSONArray()
        rows.forEach { array.put(it) }
        check(prefs.edit().putString("captures",array.toString()).commit()) {
            "გადაღებების რიგის შენახვა ვერ მოხერხდა"
        }
    }
    private fun fromJson(o:JSONObject):Job? = runCatching {
        val id=o.getLong("id")
        if(id<=0L) return@runCatching null
        Job(
            id,
            RecordingPlan(
                o.getInt("final"),
                o.getInt("from"),
                o.getInt("slow"),
                o.getDouble("speed")
            ),
            o.optString("raw"),
            o.optLong("retryAfter"),
            o.optInt("attempts"),
            o.optString("error")
        )
    }.getOrNull()

    @Synchronized fun all():List<Job> = rows().mapNotNull { fromJson(it) }.sortedBy { it.id }
    @Synchronized fun count():Int=all().size
    @Synchronized fun oldest():Job?=all().firstOrNull()

    @Synchronized fun add(id:Long,plan:RecordingPlan) {
        require(id>0L)
        val all=rows()
        if(all.any { it.optLong("id")==id })return
        all.add(JSONObject()
            .put("id",id)
            .put("final",plan.finalSeconds)
            .put("from",plan.slowStartSeconds)
            .put("slow",plan.slowSeconds)
            .put("speed",plan.speed)
            .put("raw","")
            .put("retryAfter",0L)
            .put("attempts",0)
            .put("error",""))
        save(all)
    }
    @Synchronized fun downloaded(id:Long,file:File) {
        require(file.isFile && file.canonicalFile.parentFile==context.filesDir.canonicalFile)
        require(file.name.matches(Regex("MOMENTO_GOPRO_[A-Za-z0-9_.-]+\\.mp4")))
        val all=rows()
        val item=all.firstOrNull { it.optLong("id")==id } ?: error("ჩანაწერი რიგში აღარ არის")
        item.put("raw",file.name)
        item.put("retryAfter",0L)
        item.put("attempts",0)
        item.put("error","")
        save(all)
    }
    @Synchronized fun retryLater(id:Long,reason:String) {
        val all=rows()
        val item=all.firstOrNull { it.optLong("id")==id } ?: return
        val attempts=(item.optInt("attempts")+1).coerceAtMost(10)
        item.put("attempts",attempts)
        val wait=(3000L * (1L shl attempts.coerceAtMost(5))).coerceAtMost(60000L)
        item.put("retryAfter",System.currentTimeMillis()+wait)
        item.put("error",reason.take(180))
        save(all)
    }
    @Synchronized fun remove(id:Long) {
        save(rows().filterNot { it.optLong("id")==id })
    }
}
