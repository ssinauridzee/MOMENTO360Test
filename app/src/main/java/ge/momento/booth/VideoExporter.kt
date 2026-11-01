package ge.momento.booth

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import java.io.File
import kotlin.math.ceil

/** MP4/H.264 edit via Media3. GoPro Preview .ts takes the separate remux path instead. */
@UnstableApi
class VideoExporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var progressTask: Runnable? = null

    fun export(source: Uri, plan: RecordingPlan, update: (String) -> Unit, done: (Result<File>) -> Unit) {
        if (transformer != null) { done(Result.failure(IllegalStateException("ექსპორტი უკვე მიმდინარეობს"))); return }
        val (availableMs,sourceShortSide) = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, source)
                val ms=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0
                val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
                Pair(ms,if(width>0 && height>0)minOf(width,height) else 720)
            } finally { retriever.release() }
        } catch (e: Exception) {
            done(Result.failure(e)); return
        }
        val requestedMs = ceil(plan.realCaptureSeconds * 1000.0).toLong()
        if (availableMs < requestedMs) {
            done(Result.failure(IllegalArgumentException("ჩანაწერი ძალიან მოკლეა: საჭიროა მინიმუმ $requestedMs მწმ, ფაილია $availableMs მწმ")))
            return
        }
        val startUs = plan.slowStartSeconds * 1_000_000L
        val endUs = ((plan.slowStartSeconds + plan.slowSeconds * plan.speed) * 1_000_000L).toLong()
        val provider = object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float = if (timeUs >= startUs && timeUs < endUs) plan.speed.toFloat() else 1f
            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = when {
                timeUs < startUs -> startUs
                timeUs < endUs -> endUs
                else -> C.TIME_UNSET
            }
        }
        val input = MediaItem.Builder().setUri(source)
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(requestedMs).build())
            .build()
        // Deliverable is optimised for SMS/Drive. The GoPro microSD keeps full
        // camera quality. Never upscale low-resolution LRV proxies.
        val shortSide=minOf(sourceShortSide,720).coerceAtLeast(240)
        val bitrate=when {
            shortSide<=480 -> 1_100_000
            shortSide<=600 -> 1_650_000
            else -> 2_600_000
        }
        val edited = EditedMediaItem.Builder(input)
            .setRemoveAudio(true)
            .setEffects(Effects(emptyList(), listOf(
                SpeedChangeEffect(provider),
                Presentation.createForShortSide(shortSide)
            )))
            .build()
        val file = File(context.filesDir, "MOMENTO_${System.currentTimeMillis()}.mp4")
        var progressRunnable: Runnable? = null
        var current: Transformer? = null
        fun finish(result: Result<File>) {
            if (transformer == null || transformer !== current) return
            transformer = null
            progressRunnable?.let { main.removeCallbacks(it) }
            progressTask = null
            if (result.isFailure) file.delete()
            done(result)
        }
        val encoders=DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()
        val instance = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoders)
            .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                update("ვიდეო მზადაა — შეინახე MP4")
                finish(Result.success(file))
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                finish(Result.failure(exportException))
            }
        }).build()
        current = instance
        transformer = instance
        val startedAt = SystemClock.elapsedRealtime()
        val timeoutMs = maxOf(120_000L, plan.finalSeconds * 4_000L)
        val holder = ProgressHolder()
        try {
            update("MP4 დამუშავება იწყება… • ${shortSide}p / ~${bitrate/1000} kbps")
            instance.start(edited, file.absolutePath)
            progressRunnable = object : Runnable {
                override fun run() {
                    if (transformer !== instance) return
                    if (SystemClock.elapsedRealtime() - startedAt >= timeoutMs) {
                        instance.cancel()
                        finish(Result.failure(IllegalStateException("ვიდეოს დამუშავება დროში გაიჭედა ($timeoutMs მწმ). სცადე ხელახლა ან გამოიყენე სხვა MP4.")))
                        return
                    }
                    runCatching {
                        val progressState = instance.getProgress(holder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            update("MP4 მუშავდება: ${holder.progress}%")
                        } else if (progressState == Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                            update("MP4 იწყებს დამუშავებას…")
                        } else if (progressState == Transformer.PROGRESS_STATE_UNAVAILABLE) {
                            update("MP4 მუშავდება (პროცენტი მიუწვდომელია)…")
                        }
                    }.onFailure { update("MP4 მიმდინარეობს; პროგრესის მიღება ვერ მოხერხდა…") }
                    main.postDelayed(this, 1000L)
                }
            }
            progressTask = progressRunnable
            main.postDelayed(requireNotNull(progressRunnable), 1000L)
        } catch (e: Exception) {
            finish(Result.failure(e))
        }
    }

    fun cancel() {
        progressTask?.let { main.removeCallbacks(it) }
        progressTask = null
        transformer?.cancel()
        transformer = null
    }
}
