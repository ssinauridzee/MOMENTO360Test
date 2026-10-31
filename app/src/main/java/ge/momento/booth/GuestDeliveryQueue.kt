package ge.momento.booth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persisted guest-to-video-to-link mapping. The caller MUST only markUploaded
 * after the Google Drive /complete endpoint has confirmed the MP4 is ready.
 * One guest's phone never depends on MainActivity's mutable current session.
 */
class GuestDeliveryQueue(private val context: Context) {
    data class Ticket(
        val id: Long,
        val phone: String,
        val name: String,
        val fileName: String,
        val token: String,
        val url: String,
        val uploaded: Boolean,
        val state: String,
        val retryAt: Long,
        val attempts: Int
    )
    private val prefs = context.getSharedPreferences("momento_guest_sms_v14", Context.MODE_PRIVATE)

    private fun read(): MutableList<JSONObject> {
        val a = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
    }
    private fun write(items: List<JSONObject>) {
        val a=JSONArray()
        items.forEach { a.put(it) }
        check(prefs.edit().putString("jobs",a.toString()).commit()) {
            "სტუმრის ნომერი ვერ შეინახა ტელეფონმა"
        }
    }
    private fun entry(id: Long) = JSONObject()
        .put("id", id).put("phone","").put("name","").put("file","")
        .put("token","").put("url","").put("uploaded",false)
        .put("state","pending").put("retryAt",0L).put("attempts",0)
        .put("changed", System.currentTimeMillis())
    private fun update(id: Long, change: (JSONObject) -> Unit) {
        require(id > 0)
        val list=read()
        var item=list.firstOrNull { it.optLong("id") == id }
        if(item==null) {
            item=entry(id)
            list.add(item)
        }
        change(item)
        item.put("changed", System.currentTimeMillis())
        // Preserve the last 300 guest sessions. Delete only entries with no pending SMS
        // if old history would otherwise grow indefinitely.
        val keep=if(list.size<=300)list else {
            val candidates=list.filter {
                it.optString("phone").isNotBlank() && it.optString("state")!="sent"
            }
            (candidates + list.filterNot { it in candidates }.takeLast(300)).distinctBy { it.optLong("id") }
        }
        write(keep)
    }

    @Synchronized fun recordPhone(id:Long, phone:String, name:String) {
        val normalized=phone.filter { it.isDigit() }
        require(Regex("(?:995)?5[0-9]{8}").matches(normalized)) {
            "ჩაწერე ქართული მობილურის ნომერი: 5XXXXXXXX"
        }
        val local=if(normalized.startsWith("995"))normalized.substring(3) else normalized
        update(id) {
            it.put("phone","995"+local)
            it.put("name",name.trim().take(80))
            if(it.optString("state") != "sent") {
                it.put("state","pending")
                it.put("retryAt",0L)
            }
        }
    }

    @Synchronized fun attachFile(id:Long,file:File) {
        require(file.isFile && file.extension.equals("mp4",true) &&
            file.canonicalFile.parentFile == context.filesDir.canonicalFile)
        update(id) { it.put("file",file.name) }
    }

    @Synchronized fun attachSession(fileName:String,s:Momento360Api.Session) {
        val all=read()
        var changed=false
        all.filter { it.optString("file")==fileName }.forEach {
            it.put("token",s.token)
            it.put("url",s.url)
            changed=true
        }
        if(changed)write(all)
    }

    @Synchronized fun markUploaded(token:String) {
        val all=read()
        var changed=false
        all.filter { it.optString("token")==token }.forEach {
            it.put("uploaded",true)
            changed=true
        }
        if(changed)write(all)
    }

    private fun Ticket(row:JSONObject) = Ticket(
        id=row.optLong("id"),
        phone=row.optString("phone"),
        name=row.optString("name"),
        fileName=row.optString("file"),
        token=row.optString("token"),
        url=row.optString("url"),
        uploaded=row.optBoolean("uploaded",false),
        state=row.optString("state","pending"),
        retryAt=row.optLong("retryAt"),
        attempts=row.optInt("attempts")
    )

    /** Reserve exactly one eligible SMS for dispatch, on the main thread. */
    @Synchronized fun claimReady(): Ticket? {
        val now=System.currentTimeMillis()
        val all=read()
        val item=all.firstOrNull {
            val status=it.optString("state")
            it.optString("phone").isNotBlank() &&
            it.optBoolean("uploaded",false) &&
            it.optString("token").isNotBlank() &&
            (status=="pending" && it.optLong("retryAt")<=now ||
             status=="sending" && it.optLong("changed")+120000L<=now)
        } ?: return null
        item.put("state","sending")
        item.put("changed",now)
        write(all)
        return Ticket(item)
    }

    @Synchronized fun markSent(id:Long) {
        update(id) {
            it.put("state","sent")
            // Do not retain guest phone numbers after the SMS gateway accepts them.
            it.put("phone","")
            it.put("name","")
            it.put("retryAt",0L)
        }
    }

    @Synchronized fun retryLater(id:Long,reason:String) {
        update(id) {
            val attempts=(it.optInt("attempts")+1).coerceAtMost(8)
            it.put("attempts",attempts)
            it.put("state","pending")
            it.put("lastError",reason.take(140))
            val delay=(15000L * (1L shl attempts.coerceAtMost(5))).coerceAtMost(300000L)
            it.put("retryAt",System.currentTimeMillis()+delay)
        }
    }

    @Synchronized fun pendingCount()=read().count {
        it.optString("phone").isNotBlank() && it.optString("state")!="sent"
    }
    @Synchronized fun latestError():String? = read().asReversed().firstOrNull {
        it.optString("lastError").isNotBlank() && it.optString("phone").isNotBlank()
    }?.optString("lastError")
}
