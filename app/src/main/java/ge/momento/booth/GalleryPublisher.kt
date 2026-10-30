package ge.momento.booth

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlin.concurrent.thread

/** Save the PROCESSED MP4, not the untouched GoPro original, in Movies/MOMENTO360. */
object GalleryPublisher {
    fun publish(context:Context,file:File,done:(Result<String>)->Unit){
        thread(name="momento-gallery-save"){
            val result=runCatching{
                require(file.isFile && file.length()>1024){"დამუშავებული MP4 ვერ მოიძებნა"}
                require(Build.VERSION.SDK_INT>=29){"Android 10+ აუცილებელია ავტომატური Gallery შენახვისთვის"}
                val prefs=context.getSharedPreferences("momento_gallery_v12",Context.MODE_PRIVATE)
                if(prefs.getBoolean(file.name,false))return@runCatching "Movies/MOMENTO360/"+file.name
                val resolver=context.contentResolver
                val values=ContentValues().apply{
                    put(MediaStore.Video.Media.DISPLAY_NAME,file.name)
                    put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/MOMENTO360")
                    put(MediaStore.Video.Media.IS_PENDING,1)
                }
                val uri=resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
                    ?:error("გალერეაში MP4 ვერ შეიქმნა")
                try{
                    resolver.openOutputStream(uri)?.use{output->
                        file.inputStream().buffered().use{input->input.copyTo(output)}
                    }?:error("გალერეაში ჩაწერა ვერ მოხერხდა")
                    resolver.update(uri,ContentValues().apply{put(MediaStore.Video.Media.IS_PENDING,0)},null,null)
                    prefs.edit().putBoolean(file.name,true).apply()
                    "Movies/MOMENTO360/"+file.name
                }catch(e:Exception){
                    runCatching{resolver.delete(uri,null,null)}
                    throw e
                }
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post{done(result)}
        }
    }
}
