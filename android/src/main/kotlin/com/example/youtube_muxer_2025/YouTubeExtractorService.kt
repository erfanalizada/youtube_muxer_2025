package com.example.youtube_muxer_2025

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class YouTubeExtractorService {

    companion object {
        private const val TAG = "YouTubeExtractorService"
        private var initialized = false

        /** Read/write buffer size per thread */
        private const val BUFFER_SIZE = 512 * 1024 // 512 KB

        /** Minimum progress callback interval to avoid flooding Flutter */
        private const val PROGRESS_INTERVAL_MS = 150L

        /** Files smaller than this won't be chunked */
        private const val MIN_CHUNK_SIZE = 1L * 1024 * 1024 // 1 MB

        /** Bytes downloaded before updating the shared progress counter */
        private const val PROGRESS_BATCH_BYTES = 1L * 1024 * 1024 // 1 MB

        /** Scale chunk count by file size */
        private fun chunkCountFor(fileSize: Long): Int = when {
            fileSize >= 50L * 1024 * 1024 -> 16  // 50 MB+ → 16 connections
            fileSize >= 10L * 1024 * 1024 -> 12  // 10 MB+ → 12 connections
            else -> 8
        }

        fun ensureInitialized() {
            if (!initialized) {
                NewPipe.init(NewPipeDownloaderImpl.instance)
                initialized = true
            }
        }

        /**
         * Cache StreamInfo by URL for up to 5 minutes.
         * YouTube sometimes returns different delivery methods (PROGRESSIVE_HTTP vs
         * DASH) on separate calls to the same URL — caching ensures getQualities()
         * and downloadAudio() always see the same set of streams.
         */
        private val streamInfoCache = java.util.concurrent.ConcurrentHashMap<String, Pair<StreamInfo, Long>>()
        private const val STREAM_INFO_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    }

    // Tuned for maximum parallel throughput
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(40, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1)) // separate TCP connections per request
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Thread pool for chunk downloads (video + audio chunks share this pool)
    private val chunkPool: ExecutorService = Executors.newFixedThreadPool(34)

    // ──────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns all available streams for a URL.
     * Video streams have fps > 0; audio-only streams have fps == 0.
     * The Flutter side uses fps to distinguish them.
     */
    fun getQualities(url: String): List<Map<String, Any>> {
        ensureInitialized()

        val streamInfo = getStreamInfoCached(url)

        // ── Video streams (H.264 MP4 only) ──────────────────────────────
        val videoStreams = streamInfo.videoOnlyStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null &&
                stream.format!!.mimeType.contains("video/mp4") &&
                (stream.codec?.contains("avc", ignoreCase = true) == true ||
                 stream.codec?.contains("h264", ignoreCase = true) == true)
            }

        // ── Audio streams — prefer MP4/AAC, fall back to any progressive format ──
        val allAudio = streamInfo.audioStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null
            }
        val audioStreams = allAudio
            .filter { it.format!!.mimeType.contains("audio/mp4") }
            .ifEmpty { allAudio }

        if (audioStreams.isEmpty()) {
            throw Exception("No compatible audio stream found for this video")
        }

        val seen = mutableSetOf<String>()
        val qualities = mutableListOf<Map<String, Any>>()

        // Add video qualities
        for (stream in videoStreams) {
            val label = stream.resolution ?: continue
            if (!seen.add("v_$label")) continue
            qualities.add(mapOf(
                "quality" to label,
                "url" to (stream.content ?: ""),
                "size" to (stream.itagItem?.contentLength ?: 0L),
                "container" to "mp4",
                "codec" to (stream.codec ?: "avc1"),
                "bitrate" to stream.bitrate,
                "fps" to stream.fps,
                "title" to (streamInfo.name ?: "video")
            ))
        }

        // Add audio qualities — fps = 0 distinguishes them from video
        for (stream in audioStreams) {
            val bitrateKbps = stream.averageBitrate / 1000
            val label = "${bitrateKbps}kbps"
            if (!seen.add("a_$label")) continue
            qualities.add(mapOf(
                "quality" to label,
                "url" to (stream.content ?: ""),
                "size" to (stream.itagItem?.contentLength ?: 0L),
                "container" to "m4a",
                "codec" to (stream.codec ?: "mp4a"),
                "bitrate" to stream.averageBitrate,
                "fps" to 0,
                "title" to (streamInfo.name ?: "video")
            ))
        }

        // Sort: video by resolution desc, then audio by bitrate desc
        qualities.sortWith(compareByDescending<Map<String, Any>> { (it["fps"] as Int) > 0 }
            .thenByDescending { map ->
                val fps = map["fps"] as Int
                if (fps > 0) (map["quality"] as String).replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                else map["bitrate"] as Int
            })

        return qualities
    }

    /**
     * Downloads only the best-quality audio stream for [url].
     * Progress is reported in range 0.0–1.0.
     * [onTitleKnown] fires synchronously once the video title is resolved (before download starts).
     * Returns the path to the downloaded audio file.
     */
    fun downloadAudio(
        url: String,
        tempDir: String,
        onTitleKnown: ((String) -> Unit)? = null,
        progressCallback: (Double, String) -> Unit
    ): String {
        ensureInitialized()

        val streamInfo = getStreamInfoCached(url)
        onTitleKnown?.invoke(streamInfo.name ?: "video")

        val allAudioStreams = streamInfo.audioStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null
            }
        // Prefer AAC/MP4 for maximum compatibility; fall back to any progressive stream
        val audioStream = allAudioStreams
            .filter { it.format!!.mimeType.contains("audio/mp4") }
            .maxByOrNull { it.averageBitrate }
            ?: allAudioStreams.maxByOrNull { it.averageBitrate }
            ?: throw Exception("No compatible audio stream found")

        val audioExt = when {
            audioStream.format!!.mimeType.contains("audio/mp4") -> "m4a"
            audioStream.format!!.mimeType.contains("audio/webm") -> "webm"
            else -> "m4a"
        }
        val audioSize = audioStream.itagItem?.contentLength ?: -1L
        val tempAudioPath = "$tempDir/temp_audio_dl.$audioExt"
        val lastProgressTime = AtomicLong(0)

        downloadFileChunked(audioStream.content, tempAudioPath, audioSize) { downloaded, total ->
            val now = System.currentTimeMillis()
            if (now - lastProgressTime.get() >= PROGRESS_INTERVAL_MS) {
                lastProgressTime.set(now)
                val fraction = if (total > 0) (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0) else 0.0
                progressCallback(fraction, "Downloading audio...")
            }
        }

        return tempAudioPath
    }

    /**
     * Downloads video + audio streams **in parallel**, each using multi-
     * connection chunked downloading.  Progress is reported as a combined
     * fraction (0.0 – 0.85, leaving 0.85 – 1.0 for muxing).
     * [onTitleKnown] fires synchronously once the video title is resolved (before download starts).
     */
    fun downloadStreams(
        url: String,
        qualityLabel: String,
        tempDir: String,
        onTitleKnown: ((String) -> Unit)? = null,
        progressCallback: (Double, String) -> Unit
    ): Pair<String, String> {
        ensureInitialized()

        val streamInfo = getStreamInfoCached(url)
        onTitleKnown?.invoke(streamInfo.name ?: "video")

        val videoStream = run {
            val candidates = streamInfo.videoOnlyStreams
                .filter { stream ->
                    stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    stream.format != null &&
                    stream.format!!.mimeType.contains("video/mp4")
                }
            val targetRes = Regex("\\d+").find(qualityLabel)?.value?.toIntOrNull()
            // 1. Exact label match
            candidates.firstOrNull { it.resolution == qualityLabel }
            // 2. Same resolution number — handles "720p" vs "720p60" label drift
            ?: candidates.firstOrNull { s ->
                Regex("\\d+").find(s.resolution ?: "")?.value?.toIntOrNull() == targetRes
            }
            // 3. Nearest available resolution as last resort
            ?: candidates
                .mapNotNull { s ->
                    val n = Regex("\\d+").find(s.resolution ?: "")?.value?.toIntOrNull()
                    if (n != null && targetRes != null) Pair(s, Math.abs(n - targetRes)) else null
                }
                .minByOrNull { it.second }?.first
        } ?: throw Exception("No video stream available (requested: '$qualityLabel')")

        val allProgressiveAudio = streamInfo.audioStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null
            }
        val audioStream = allProgressiveAudio
            .filter { it.format!!.mimeType.contains("audio/mp4") }
            .maxByOrNull { it.averageBitrate }
            ?: allProgressiveAudio.maxByOrNull { it.averageBitrate }
            ?: throw Exception("No compatible audio stream found")

        val tempVideoPath = "$tempDir/temp_video.mp4"
        val audioExt = if (audioStream.format!!.mimeType.contains("audio/mp4")) "m4a" else "webm"
        val tempAudioPath = "$tempDir/temp_audio.$audioExt"

        val videoSize = videoStream.itagItem?.contentLength ?: -1L
        val audioSize = audioStream.itagItem?.contentLength ?: -1L

        // ── Shared atomic counters for combined progress ──
        val videoDownloaded = AtomicLong(0)
        val audioDownloaded = AtomicLong(0)
        val videoTotal = AtomicLong(if (videoSize > 0) videoSize else 0)
        val audioTotal = AtomicLong(if (audioSize > 0) audioSize else 0)
        val lastProgressTime = AtomicLong(0)

        val reportProgress = {
            val now = System.currentTimeMillis()
            if (now - lastProgressTime.get() >= PROGRESS_INTERVAL_MS) {
                lastProgressTime.set(now)
                val total = videoTotal.get() + audioTotal.get()
                if (total > 0) {
                    val downloaded = videoDownloaded.get() + audioDownloaded.get()
                    val fraction = (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                    progressCallback(fraction * 0.85, "Downloading...")
                }
            }
        }

        // ── Launch video & audio downloads on dedicated threads ──
        // (NOT on chunkPool – avoids pool starvation since downloadFileChunked
        //  submits chunk tasks to chunkPool and blocks until they finish)
        val latch = CountDownLatch(2)
        val videoError = AtomicReference<Exception?>(null)
        val audioError = AtomicReference<Exception?>(null)

        Thread({
            try {
                downloadFileChunked(videoStream.content, tempVideoPath, videoSize) { bytes, total ->
                    videoDownloaded.set(bytes)
                    if (total > 0) videoTotal.set(total)
                    reportProgress()
                }
            } catch (e: Exception) {
                videoError.set(e)
            } finally {
                latch.countDown()
            }
        }, "yt-video-dl").start()

        Thread({
            try {
                downloadFileChunked(audioStream.content, tempAudioPath, audioSize) { bytes, total ->
                    audioDownloaded.set(bytes)
                    if (total > 0) audioTotal.set(total)
                    reportProgress()
                }
            } catch (e: Exception) {
                audioError.set(e)
            } finally {
                latch.countDown()
            }
        }, "yt-audio-dl").start()

        latch.await()

        videoError.get()?.let { throw Exception("Video download failed: ${it.message}", it) }
        audioError.get()?.let { throw Exception("Audio download failed: ${it.message}", it) }

        return Pair(tempVideoPath, tempAudioPath)
    }

    fun getVideoTitle(url: String): String {
        ensureInitialized()
        val streamInfo = getStreamInfoCached(url)
        return streamInfo.name ?: "video"
    }

    // ──────────────────────────────────────────────────────────────────
    //  StreamInfo cache + retry wrapper
    // ──────────────────────────────────────────────────────────────────

    /**
     * Returns [StreamInfo] for [url], serving from cache if the entry is
     * younger than [STREAM_INFO_CACHE_TTL_MS].  Caching ensures that
     * getQualities() and downloadAudio()/downloadStreams() always see the
     * same set of streams even when YouTube returns different delivery
     * methods (PROGRESSIVE_HTTP vs DASH) on back-to-back calls.
     */
    private fun getStreamInfoCached(url: String): StreamInfo {
        val now = System.currentTimeMillis()
        val cached = streamInfoCache[url]
        if (cached != null && (now - cached.second) < STREAM_INFO_CACHE_TTL_MS) {
            Log.d(TAG, "StreamInfo cache hit for $url")
            return cached.first
        }
        val info = getStreamInfoWithRetry(url)
        streamInfoCache[url] = Pair(info, System.currentTimeMillis())
        return info
    }

    /**
     * Wraps [StreamInfo.getInfo] with up to 3 attempts on transient failures.
     * Delays: 1 s after attempt 1, 3 s after attempt 2 (exponential back-off).
     * Permanent errors (geo-block, age-restriction, private/deleted video,
     * captcha) are rethrown immediately without consuming any retry budget.
     */
    private fun getStreamInfoWithRetry(url: String): StreamInfo {
        val delays = longArrayOf(1_000, 3_000)
        var lastException: Exception? = null
        for (attempt in 0..delays.size) {
            try {
                return StreamInfo.getInfo(ServiceList.YouTube, url)
            } catch (e: Exception) {
                if (!isTransientFailure(e)) throw e
                lastException = e
                if (attempt < delays.size) {
                    Log.w(TAG, "Transient failure on attempt ${attempt + 1} — retrying in ${delays[attempt]} ms… (${e.javaClass.simpleName}: ${e.message})")
                    Thread.sleep(delays[attempt])
                }
            }
        }
        throw lastException!!
    }

    /**
     * Returns true when [e] represents a failure worth retrying.
     *
     * Permanent errors ([ContentNotAvailableException], [ReCaptchaException])
     * are detected first so they are never retried even if they happen to wrap
     * an [IOException] deeper in the cause chain.
     *
     * Transient classes:
     *  - [java.io.IOException] — network/timeout/DNS failures
     *  - [org.schabi.newpipe.extractor.exceptions.ParsingException] — YouTube
     *    page format changed or incomplete response; often succeeds on retry
     *  - [org.schabi.newpipe.extractor.exceptions.ExtractionException] — broader
     *    NewPipe extraction hiccups that are not permanent content errors
     */
    private fun isTransientFailure(e: Throwable): Boolean {
        // Walk the cause chain once for permanent errors — if found, do NOT retry
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException) return false
            if (cause is org.schabi.newpipe.extractor.exceptions.ReCaptchaException) return false
            cause = cause.cause
        }
        // Walk again for transient classes that ARE worth retrying
        cause = e
        while (cause != null) {
            if (cause is java.io.IOException) return true
            if (cause is org.schabi.newpipe.extractor.exceptions.ParsingException) return true
            if (cause is org.schabi.newpipe.extractor.exceptions.ExtractionException) return true
            cause = cause.cause
        }
        return false
    }

    fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), "_")
    }

    // ──────────────────────────────────────────────────────────────────
    //  Internal download helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Downloads [url] to [outputPath] using multi-connection chunked
     * downloading.  YouTube CDN always supports Range, so we skip the
     * probe and attempt chunked directly, falling back to single-
     * connection only if the first chunk gets a non-206 response.
     */
    private fun downloadFileChunked(
        url: String,
        outputPath: String,
        expectedSize: Long,
        progressCallback: (Long, Long) -> Unit
    ) {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val contentLength = resolveContentLength(url, expectedSize)

        if (contentLength > MIN_CHUNK_SIZE) {
            try {
                downloadChunked(url, outputFile, contentLength, progressCallback)
                return
            } catch (e: RangeFallbackException) {
                Log.d(TAG, "Range not supported, falling back to single connection")
            }
        }
        downloadSingle(url, outputFile, contentLength, progressCallback)
    }

    /** Thrown when the server returns 200 instead of 206, signalling no Range support. */
    private class RangeFallbackException : Exception()

    /** Multi-connection chunked download using Range headers and FileChannel. */
    private fun downloadChunked(
        url: String,
        outputFile: File,
        contentLength: Long,
        progressCallback: (Long, Long) -> Unit
    ) {
        val chunkCount = chunkCountFor(contentLength)
        val chunkSize = contentLength / chunkCount
        val downloadedBytes = AtomicLong(0)

        // Pre-allocate the output file
        RandomAccessFile(outputFile, "rw").use { it.setLength(contentLength) }

        val latch = CountDownLatch(chunkCount)
        val firstError = AtomicReference<Exception?>(null)

        for (i in 0 until chunkCount) {
            val start = i * chunkSize
            val end = if (i == chunkCount - 1) contentLength - 1 else (start + chunkSize - 1)

            chunkPool.submit {
                try {
                    if (firstError.get() != null) return@submit

                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$start-$end")
                        .build()

                    val response = httpClient.newCall(request).execute()

                    // If the server ignores Range and returns 200, signal fallback
                    if (response.code == 200) {
                        response.close()
                        throw RangeFallbackException()
                    }
                    if (!response.isSuccessful && response.code != 206) {
                        throw Exception("Chunk $i failed: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty body for chunk $i")
                    val buffer = ByteArray(BUFFER_SIZE)
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    var localBytes = 0L

                    body.byteStream().use { input ->
                        RandomAccessFile(outputFile, "rw").use { raf ->
                            val channel = raf.channel
                            var pos = start
                            while (pos <= end) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                byteBuffer.clear()
                                byteBuffer.limit(bytesRead)
                                channel.write(byteBuffer, pos)
                                pos += bytesRead
                                localBytes += bytesRead
                                // Batch progress: only update shared counter every PROGRESS_BATCH_BYTES
                                if (localBytes >= PROGRESS_BATCH_BYTES) {
                                    downloadedBytes.addAndGet(localBytes)
                                    localBytes = 0L
                                    progressCallback(downloadedBytes.get(), contentLength)
                                }
                            }
                        }
                    }
                    // Flush remaining local bytes
                    if (localBytes > 0) {
                        downloadedBytes.addAndGet(localBytes)
                        progressCallback(downloadedBytes.get(), contentLength)
                    }
                } catch (e: RangeFallbackException) {
                    firstError.compareAndSet(null, e)
                } catch (e: Exception) {
                    firstError.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        firstError.get()?.let { throw it }
        Log.d(TAG, "Chunked download done: ${downloadedBytes.get()} bytes (${chunkCount} chunks) → ${outputFile.path}")
    }

    /** Single-connection fallback with large buffered I/O. */
    private fun downloadSingle(
        url: String,
        outputFile: File,
        expectedSize: Long,
        progressCallback: (Long, Long) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Download failed: HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val totalBytes = if (expectedSize > 0) expectedSize else body.contentLength()

        var downloadedBytes = 0L
        val buffer = ByteArray(BUFFER_SIZE)

        body.byteStream().use { input ->
            BufferedOutputStream(FileOutputStream(outputFile), BUFFER_SIZE).use { output ->
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    progressCallback(downloadedBytes, totalBytes)
                }
            }
        }

        Log.d(TAG, "Single download done: $downloadedBytes bytes → ${outputFile.path}")
    }

    // ──────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────

    private fun resolveContentLength(url: String, expectedSize: Long): Long {
        if (expectedSize > 0) return expectedSize
        return try {
            val request = Request.Builder().url(url).head().build()
            httpClient.newCall(request).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

}
