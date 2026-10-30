package ge.momento.booth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small persistent queue for completed MP4 videos. Retry never loses the URL/upload key
 * when another guest starts recording. Access limited to the app's private filesDir.
 * All mutations are persisted synchronously to survive a process shutdown.
 */
class PendingUploadQueue(private val context: Context) {
    data class Job(val session: Momento360Api.Session, val file: File)
    private val prefs = context.getSharedPreferences("m360_upload_queue_v1", Context.MODE_PRIVATE)

    @Synchronized fun all(): List<Job> {
        val data = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        val jobs = mutableListOf<Job>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val token = obj.optString("token")
            val key = obj.optString("key")
            val url = obj.optString("url")
            val filename = obj.optString("file")
            if (!Regex("[A-Za-z0-9_-]{16,64}").matches(token) || key.length < 32 ||
                !url.startsWith("https://momento.ge/v/") || filename.contains('/') || filename.contains('\\')) continue
            val f = File(context.filesDir, filename)
            if (f.isFile && f.length() >= 16 && f.canonicalFile.parentFile == context.filesDir.canonicalFile) {
                jobs.add(Job(Momento360Api.Session(token, key, url), f))
            }
        }
        return jobs
    }

    @Synchronized fun add(session: Momento360Api.Session, file: File) {
        require(file.isFile && file.extension.equals("mp4", ignoreCase = true) &&
            file.canonicalFile.parentFile == context.filesDir.canonicalFile) { "ვიდეო უნდა მდებარეობდეს აპლიკაციის პირად საქაღალდეში" }
        val items = all().filterNot { it.session.token == session.token }.toMutableList()
        items.add(Job(session, file))
        write(items)
    }

    @Synchronized fun remove(token: String) { write(all().filterNot { it.session.token == token }) }
    @Synchronized fun size(): Int = all().size

    private fun write(items: List<Job>) {
        val a = JSONArray()
        for (j in items) a.put(JSONObject().put("token", j.session.token)
            .put("key", j.session.uploadKey).put("url", j.session.url).put("file", j.file.name))
        check(prefs.edit().putString("items", a.toString()).commit()) { "ვიდეოს ატვირთვის რიგი ვერ შეინახა" }
    }
}
