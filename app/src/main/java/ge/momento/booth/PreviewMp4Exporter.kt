package ge.momento.booth

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * HERO9 Preview TS -> MP4 for the SD-less mode. Works on already-encoded video samples,
 * not on still images. Slows playback by stretching video sample presentation timestamps.
 * A 30 fps input at 0.25x is ~7.5 distinct fps in the slowed region: it is NOT 240 fps.
 *
 * No FFmpeg, native shell or third-party binary needed. Android MediaExtractor may reject
 * some GoPro MPEG-TS files: report that error and retain the original TS for diagnosis.
 */
class PreviewMp4Exporter(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var deadlineTask: Runnable? = null
    @Volatile private var jobId: Int = 0

    fun export(source: File, plan: RecordingPlan, update: (String) -> Unit, done: (Result<File>) -> Unit) {
        if (!running.compareAndSet(false, true)) {
            done(Result.failure(IllegalStateException("Preview MP4 ექსპორტი უკვე მუშაობს")))
            return
        }
        cancelled.set(false)
        val id = ++jobId
        val output = File(context.filesDir, "MOMENTO_preview_${System.currentTimeMillis()}.mp4")
        // Finite upper bound: users must never wait silently forever.
        val timeoutMs = maxOf(75_000L, plan.finalSeconds * 2_500L)
        val deadline = Runnable {
            if (id != jobId || !running.compareAndSet(true, false)) return@Runnable
            cancelled.set(true)
            update("დამუშავებამ დრო გადააჭარბა (${timeoutMs / 1000} წმ). ორიგინალი Preview შენახულია; გამოიყენე «Preview TS-ის შენახვა». ")
            done(Result.failure(IllegalStateException("ტელეფონმა MP4-ის შექმნა ვერ დაასრულა დროულად. ორიგინალი Preview შენარჩუნებულია.")))
        }
        deadlineTask = deadline
        main.postDelayed(deadline, timeoutMs)
        update("Preview TS → MP4: ვიდეონაკადის შემოწმება…")
        thread(name = "momento-preview-mp4", isDaemon = true) {
            val result = runCatching {
                require(source.isFile && source.length() > 1024L) { "Preview ფაილი არ მოიძებნა ან ცარიელია" }
                convert(source, output, plan, id, update)
                output
            }
            main.post {
                if (id != jobId || !running.compareAndSet(true, false)) {
                    output.delete()
                    return@post
                }
                main.removeCallbacks(deadline)
                deadlineTask = null
                if (result.isFailure) output.delete()
                done(result)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        jobId++
        running.set(false)
        deadlineTask?.let { main.removeCallbacks(it) }
        deadlineTask = null
    }

    private fun convert(source: File, output: File, plan: RecordingPlan, id: Int, update: (String) -> Unit) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxStarted = false
        var successful = false
        try {
            extractor.setDataSource(source.absolutePath)
            var videoTrack = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    videoTrack = index
                    break
                }
            }
            require(videoTrack >= 0) { "ამ მოწყობილობის MediaExtractor ვერ კითხულობს GoPro Preview-ის TS ვიდეოკადრებს. Preview TS შეინახე და გამოგვიგზავნე." }
            val videoFormat = extractor.getTrackFormat(videoTrack)
            extractor.selectTrack(videoTrack)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val mp4Track = muxer.addTrack(videoFormat)
            muxer.start()
            muxStarted = true
            val mux = requireNotNull(muxer)
            val suggestedSize = runCatching { videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrDefault(1024 * 1024)
            val buffer = ByteBuffer.allocateDirect(suggestedSize.coerceIn(1024 * 1024, 8 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val sourceEndUs = (plan.realCaptureSeconds * 1_000_000).roundToLong()
            var firstTimeUs = -1L
            var previousTimeUs = -1L
            var firstKeyframe = false
            var count = 0
            var lastReport = 0L
            var latestSourceUs = 0L
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                if (cancelled.get() || id != jobId) error("დამუშავება შეწყდა")
                // Android native extractors can hang. The main-thread deadline will still fire.
                if (SystemClock.elapsedRealtime() - startedAt > maxOf(65_000L, plan.finalSeconds * 2_200L)) error("MP4 დამუშავებამ დრო გადააჭარბა")
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0) break
                val flags = extractor.sampleFlags
                // Begin from a sync frame so the MP4 can actually decode from its first sample.
                if (!firstKeyframe) {
                    if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                        if (!extractor.advance()) break
                        continue
                    }
                    firstKeyframe = true
                    firstTimeUs = sampleUs
                }
                val relativeUs = (sampleUs - firstTimeUs).coerceAtLeast(0L)
                if (relativeUs > sourceEndUs + 25_000L) break
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break
                require(size <= buffer.capacity()) { "კადრი მეტისმეტად დიდია: $size" }
                val pts = PreviewTimeline.toOutputTimeUs(relativeUs, plan)
                    .coerceAtLeast(previousTimeUs + 1L)
                info.set(0, size, pts, if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buffer.position(0)
                buffer.limit(size)
                mux.writeSampleData(mp4Track, buffer, info)
                previousTimeUs = pts
                latestSourceUs = relativeUs
                count++
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport > 650L) {
                    lastReport = now
                    val pc = (100.0 * relativeUs / sourceEndUs.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                    main.post { if (id == jobId && running.get()) update("Preview → MP4: $pc% (კადრი $count)") }
                }
                if (!extractor.advance()) break
            }
            require(count >= 5) { "Preview ფაილში საკმარისი ვიდეოკადრები ვერ მოიძებნა (კადრები: $count)" }
            require(latestSourceUs >= sourceEndUs - 350_000L) {
                "Preview ჩანაწერი ძალიან მოკლეა Slow Motion გეგმისთვის: ${latestSourceUs / 1_000_000.0} წმ / საჭიროა ${plan.realCaptureSeconds} წმ"
            }
            mux.stop()
            muxStarted = false
            mux.release()
            muxer = null
            require(output.length() > 1024L) { "MP4 ცარიელია" }
            successful = true
            main.post { if (id == jobId && running.get()) update("MP4 მზადაა: ${output.length() / 1024} KB — შენელება გათვალისწინებულია") }
        } finally {
            runCatching { extractor.release() }
            if (muxStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (!successful) output.delete()
        }
    }
}
