package ge.momento.booth

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Offline-first queue: finished Slow Motion MP4 files stay here until a WordPress
 * share session and a persistent Google Drive upload job have been created.
 */
class PreparedVideoQueue(private val context: Context) {
    private val prefs=context.getSharedPreferences("momento_prepared_v12",Context.MODE_PRIVATE)

    @Synchronized fun all():List<File>{
        val array=runCatching{JSONArray(prefs.getString("files","[]"))}.getOrElse{JSONArray()}
        val result=ArrayList<File>()
        for(i in 0 until array.length()){
            val name=array.optString(i)
            if(!name.matches(Regex("MOMENTO_[A-Za-z0-9_.-]+\\.mp4",RegexOption.IGNORE_CASE)))continue
            val file=File(context.filesDir,name)
            if(file.isFile && file.length()>1024 && file.canonicalFile.parentFile==context.filesDir.canonicalFile)
                result.add(file)
        }
        return result
    }

    @Synchronized fun add(file:File){
        require(file.isFile && file.canonicalFile.parentFile==context.filesDir.canonicalFile){"არასწორი MP4"}
        write(all().filterNot{it.name==file.name}+file)
    }
    @Synchronized fun remove(file:File){write(all().filterNot{it.name==file.name})}
    @Synchronized fun size():Int=all().size

    private fun write(files:List<File>){
        val array=JSONArray()
        files.forEach{array.put(it.name)}
        check(prefs.edit().putString("files",array.toString()).commit()){"ვიდეოს მოლოდინის რიგი ვერ შეინახა"}
    }
}
