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

        /** Number of parallel connections per file download */
        private const val CHUNK_COUNT = 8

        /** Read/write buffer size per thread */
        private const val BUFFER_SIZE = 512 * 1024 // 512 KB

        /** Minimum progress callback interval to avoid flooding Flutter */
        private const val PROGRESS_INTERVAL_MS = 150L

        /** Files smaller than this won't be chunked */
        private const val MIN_CHUNK_SIZE = 1L * 1024 * 1024 // 1 MB

        fun ensureInitialized() {
            if (!initialized) {
                NewPipe.init(NewPipeDownloaderImpl.instance)
                initialized = true
            }
        }
    }

    // Tuned for maximum parallel throughput
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1)) // separate TCP connections per request
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Thread pool for chunk downloads (video + audio chunks share this pool)
    private val chunkPool: ExecutorService = Executors.newFixedThreadPool(20)

    // ──────────────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────────────

    fun getQualities(url: String): List<Map<String, Any>> {
        ensureInitialized()

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)

        val videoStreams = streamInfo.videoOnlyStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null &&
                stream.format!!.mimeType.contains("video/mp4") &&
                (stream.codec?.contains("avc", ignoreCase = true) == true ||
                 stream.codec?.contains("h264", ignoreCase = true) == true)
            }

        val hasAudio = streamInfo.audioStreams.any { stream ->
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
            stream.format != null &&
            stream.format!!.mimeType.contains("audio/mp4")
        }

        if (!hasAudio) {
            throw Exception("No compatible audio stream found for this video")
        }

        val seen = mutableSetOf<String>()
        val qualities = mutableListOf<Map<String, Any>>()

        for (stream in videoStreams) {
            val label = stream.resolution ?: continue
            if (!seen.add(label)) continue

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

        qualities.sortByDescending { map ->
            val q = map["quality"] as String
            q.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        }

        return qualities
    }

    /**
     * Downloads video + audio streams **in parallel**, each using multi-
     * connection chunked downloading.  Progress is reported as a combined
     * fraction (0.0 – 0.85, leaving 0.85 – 1.0 for muxing).
     */
    fun downloadStreams(
        url: String,
        qualityLabel: String,
        tempDir: String,
        progressCallback: (Double, String) -> Unit
    ): Pair<String, String> {
        ensureInitialized()

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)

        val videoStream = streamInfo.videoOnlyStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null &&
                stream.format!!.mimeType.contains("video/mp4")
            }
            .firstOrNull { it.resolution == qualityLabel }
            ?: throw Exception("Selected quality '$qualityLabel' no longer available")

        val audioStream = streamInfo.audioStreams
            .filter { stream ->
                stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                stream.format != null &&
                stream.format!!.mimeType.contains("audio/mp4")
            }
            .maxByOrNull { it.averageBitrate }
            ?: throw Exception("No compatible audio stream found")

        val tempVideoPath = "$tempDir/temp_video.mp4"
        val tempAudioPath = "$tempDir/temp_audio.m4a"

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
        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)
        return streamInfo.name ?: "video"
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
     * downloading when the server supports Range requests, falling back
     * to a single fast-buffered connection otherwise.
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

        if (contentLength > MIN_CHUNK_SIZE && supportsRangeRequests(url)) {
            downloadChunked(url, outputFile, contentLength, progressCallback)
        } else {
            downloadSingle(url, outputFile, contentLength, progressCallback)
        }
    }

    /** Multi-connection chunked download using Range headers. */
    private fun downloadChunked(
        url: String,
        outputFile: File,
        contentLength: Long,
        progressCallback: (Long, Long) -> Unit
    ) {
        val chunkSize = contentLength / CHUNK_COUNT
        val downloadedBytes = AtomicLong(0)

        // Pre-allocate the output file
        RandomAccessFile(outputFile, "rw").use { it.setLength(contentLength) }

        val latch = CountDownLatch(CHUNK_COUNT)
        val firstError = AtomicReference<Exception?>(null)

        for (i in 0 until CHUNK_COUNT) {
            val start = i * chunkSize
            val end = if (i == CHUNK_COUNT - 1) contentLength - 1 else (start + chunkSize - 1)

            chunkPool.submit {
                try {
                    if (firstError.get() != null) return@submit

                    val request = Request.Builder()
                        .url(url)
                        .header("Range", "bytes=$start-$end")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful && response.code != 206) {
                        throw Exception("Chunk $i failed: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw Exception("Empty body for chunk $i")
                    val buffer = ByteArray(BUFFER_SIZE)

                    body.byteStream().use { input ->
                        RandomAccessFile(outputFile, "rw").use { raf ->
                            raf.seek(start)
                            var pos = start
                            while (pos <= end) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                raf.write(buffer, 0, bytesRead)
                                pos += bytesRead
                                downloadedBytes.addAndGet(bytesRead.toLong())
                                progressCallback(downloadedBytes.get(), contentLength)
                            }
                        }
                    }
                } catch (e: Exception) {
                    firstError.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        firstError.get()?.let { throw it }
        Log.d(TAG, "Chunked download done: ${downloadedBytes.get()} bytes → ${outputFile.path}")
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

    private fun supportsRangeRequests(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .build()
            httpClient.newCall(request).execute().use { it.code == 206 }
        } catch (e: Exception) {
            false
        }
    }
}
