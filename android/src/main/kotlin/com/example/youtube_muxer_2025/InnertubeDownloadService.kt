package com.example.youtube_muxer_2025

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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

/**
 * YouTube downloader using the Innertube API (ANDROID client).
 *
 * Compared to the NewPipe-based approach:
 *  - No JS player / n-signature decryption needed (ANDROID client returns direct URLs)
 *  - No external library: pure OkHttp + Android org.json
 *  - Simpler and faster stream info fetch (one HTTP call vs NewPipe multi-step extraction)
 *
 * Limitation: if YouTube changes the ANDROID client to require cipher in the future,
 * streams with `signatureCipher` are skipped and the service returns fewer/no streams.
 */
class InnertubeDownloadService {

    // ── Innertube client configs ──────────────────────────────────────────────
    companion object {
        private const val TAG = "InnertubeDownloadService"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"

        // ANDROID — no JS player / cipher required; may need poToken for some videos
        private const val ANDROID_CLIENT_NAME = "ANDROID"
        private const val ANDROID_CLIENT_VERSION = "21.02.35"
        private const val ANDROID_CLIENT_ID = "3"
        private const val ANDROID_SDK = 30
        private val ANDROID_UA = "com.google.android.youtube/$ANDROID_CLIENT_VERSION (Linux; U; Android 11) gzip"

        // TVHTML5 — TV client; no defined poToken policy, often works without it
        private const val TV_CLIENT_NAME = "TVHTML5"
        private const val TV_CLIENT_VERSION = "7.20260114.12.00"
        private const val TV_CLIENT_ID = "7"
        private const val TV_UA = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1"

        // IOS — returns direct URLs, no JS player required; different stream set
        private const val IOS_CLIENT_NAME = "IOS"
        private const val IOS_CLIENT_VERSION = "21.02.3"
        private const val IOS_CLIENT_ID = "5"
        private const val IOS_UA = "com.google.ios.youtube/$IOS_CLIENT_VERSION (iPhone16,2; U; CPU iOS 18_2_1 like Mac OS X;)"

        private const val BUFFER_SIZE = 512 * 1024       // 512 KB per thread
        private const val MIN_CHUNK_SIZE = 1L * 1024 * 1024  // 1 MB threshold for chunked
        private const val PROGRESS_INTERVAL_MS = 150L

        private fun chunkCount(size: Long) = when {
            size >= 50L * 1024 * 1024 -> 16
            size >= 10L * 1024 * 1024 -> 12
            else -> 8
        }

        private val VIDEO_ID_PATTERNS = listOf(
            Regex("(?:v=|shorts/|embed/)([a-zA-Z0-9_-]{11})"),
            Regex("youtu\\.be/([a-zA-Z0-9_-]{11})")
        )
    }

    // ── HTTP clients ─────────────────────────────────────────────────────────

    /** Single connection client for Innertube API calls (JSON, small). */
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Multi-connection client for chunked media downloads. */
    private val dlClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(40, 5, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val chunkPool: ExecutorService = Executors.newFixedThreadPool(34)

    // ── Public data types ────────────────────────────────────────────────────

    data class StreamBundle(
        val videoId: String,
        val title: String,
        val audioStreams: List<AudioStream>,
        val videoStreams: List<VideoStream>
    )

    data class AudioStream(
        val url: String,
        val mimeType: String,   // "audio/mp4" or "audio/webm"
        val codec: String,
        val bitrate: Int,
        val size: Long
    )

    data class VideoStream(
        val url: String,
        val mimeType: String,   // "video/mp4" or "video/webm"
        val codec: String,
        val bitrate: Int,
        val size: Long,
        val width: Int,
        val height: Int,
        val fps: Int,
        val qualityLabel: String
    )

    // ── Public API ───────────────────────────────────────────────────────────

    fun extractVideoId(url: String): String? {
        for (p in VIDEO_ID_PATTERNS) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /**
     * Fetches stream info via Innertube.
     * Tries ANDROID client first (no cipher needed); if it returns no direct-URL streams,
     * retries with TVHTML5 (TV client, no strict poToken policy).
     */
    fun getStreamBundle(url: String): StreamBundle {
        val id = extractVideoId(url) ?: throw Exception("Cannot extract video ID from URL")

        // Try three clients in order: ANDROID → TVHTML5 → IOS
        for (clientType in listOf("ANDROID", "TV", "IOS")) {
            val bundle = try {
                fetchPlayerResponse(id, clientType).also {
                    if (it.audioStreams.isNotEmpty() || it.videoStreams.isNotEmpty()) {
                        Log.d(TAG, "$clientType client: ${it.audioStreams.size} audio, ${it.videoStreams.size} video direct-URL streams")
                        return it
                    }
                    Log.w(TAG, "$clientType client: response OK but no direct-URL streams")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$clientType client failed: ${e.message}")
                null
            }
            if (bundle == null) continue
        }
        throw Exception("All Innertube clients returned no direct-URL streams (video may require poToken)")
    }

    /**
     * Returns qualities in the same Map format used by YouTubeExtractorService,
     * so the existing Dart/Flutter side needs no changes.
     * Video streams: fps > 0 | Audio streams: fps == 0.
     */
    fun getQualities(url: String): List<Map<String, Any>> {
        val bundle = getStreamBundle(url)

        if (bundle.audioStreams.isEmpty() && bundle.videoStreams.isEmpty()) {
            throw Exception("No direct-URL streams found (video may require cipher)")
        }

        val allAudio = bundle.audioStreams
        val audioStreams = allAudio.filter { it.mimeType.contains("audio/mp4") }
            .ifEmpty { allAudio }

        if (audioStreams.isEmpty()) {
            throw Exception("No compatible audio stream found for this video")
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<Map<String, Any>>()

        // Video streams (H.264 MP4 only — MediaMuxer requires H.264)
        for (s in bundle.videoStreams) {
            if (!s.mimeType.contains("video/mp4")) continue
            if (s.codec.contains("av01", ignoreCase = true) || s.codec.contains("vp", ignoreCase = true)) continue
            if (!seen.add("v_${s.qualityLabel}")) continue
            result.add(mapOf(
                "quality" to s.qualityLabel,
                "url" to s.url,
                "size" to s.size,
                "container" to "mp4",
                "codec" to s.codec,
                "bitrate" to s.bitrate,
                "fps" to s.fps,
                "title" to bundle.title
            ))
        }

        // Audio streams (fps = 0 distinguishes them on the Flutter side)
        for (s in audioStreams) {
            val kbps = s.bitrate / 1000
            val label = "${kbps}kbps"
            if (!seen.add("a_$label")) continue
            result.add(mapOf(
                "quality" to label,
                "url" to s.url,
                "size" to s.size,
                "container" to if (s.mimeType.contains("audio/mp4")) "m4a" else "webm",
                "codec" to s.codec,
                "bitrate" to s.bitrate,
                "fps" to 0,
                "title" to bundle.title
            ))
        }

        result.sortWith(
            compareByDescending<Map<String, Any>> { (it["fps"] as Int) > 0 }
                .thenByDescending { map ->
                    val fps = map["fps"] as Int
                    if (fps > 0) (map["quality"] as String).replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                    else map["bitrate"] as Int
                }
        )
        return result
    }

    /**
     * Downloads the best audio stream to [tempDir]/temp_audio_innertube.<ext>.
     * Returns the path of the downloaded file.
     */
    fun downloadAudio(
        url: String,
        tempDir: String,
        onTitleKnown: ((String) -> Unit)? = null,
        progressCallback: (Double, String) -> Unit
    ): String {
        val bundle = getStreamBundle(url)
        onTitleKnown?.invoke(bundle.title)

        val allAudio = bundle.audioStreams
        if (allAudio.isEmpty()) throw Exception("No audio streams found")

        val audioStream = allAudio.filter { it.mimeType.contains("audio/mp4") }
            .maxByOrNull { it.bitrate }
            ?: allAudio.maxByOrNull { it.bitrate }
            ?: throw Exception("No compatible audio stream found")

        val ext = if (audioStream.mimeType.contains("audio/mp4")) "m4a" else "webm"
        val outPath = "$tempDir/temp_audio_innertube.$ext"
        val lastProgress = AtomicLong(0)

        downloadChunkedOrSingle(audioStream.url, outPath, audioStream.size) { dl, total ->
            val now = System.currentTimeMillis()
            if (now - lastProgress.get() >= PROGRESS_INTERVAL_MS) {
                lastProgress.set(now)
                val frac = if (total > 0) (dl.toDouble() / total).coerceIn(0.0, 1.0) else 0.0
                progressCallback(frac, "Downloading audio…")
            }
        }
        return outPath
    }

    /**
     * Downloads video + audio in parallel, muxes them, and returns the output path.
     * Progress is 0.0–0.85 for download, 0.85–1.0 reserved for muxing by the caller.
     */
    fun downloadStreams(
        url: String,
        qualityLabel: String,
        tempDir: String,
        onTitleKnown: ((String) -> Unit)? = null,
        progressCallback: (Double, String) -> Unit
    ): Pair<String, String> {
        val bundle = getStreamBundle(url)
        onTitleKnown?.invoke(bundle.title)

        // Select video stream
        val videoStream = run {
            val mp4Videos = bundle.videoStreams.filter { it.mimeType.contains("video/mp4") }
            val targetH = Regex("\\d+").find(qualityLabel)?.value?.toIntOrNull()
            mp4Videos.firstOrNull { it.qualityLabel == qualityLabel }
                ?: mp4Videos.firstOrNull { s ->
                    Regex("\\d+").find(s.qualityLabel)?.value?.toIntOrNull() == targetH
                }
                ?: mp4Videos.mapNotNull { s ->
                    val h = Regex("\\d+").find(s.qualityLabel)?.value?.toIntOrNull()
                    if (h != null && targetH != null) s to Math.abs(h - targetH) else null
                }.minByOrNull { it.second }?.first
        } ?: throw Exception("No video stream for quality '$qualityLabel'")

        // Select audio stream (prefer MP4/AAC for MediaMuxer)
        val allAudio = bundle.audioStreams
        val audioStream = allAudio.filter { it.mimeType.contains("audio/mp4") }
            .maxByOrNull { it.bitrate }
            ?: allAudio.maxByOrNull { it.bitrate }
            ?: throw Exception("No compatible audio stream found")

        val tempVideoPath = "$tempDir/temp_video_innertube.mp4"
        val audioExt = if (audioStream.mimeType.contains("audio/mp4")) "m4a" else "webm"
        val tempAudioPath = "$tempDir/temp_audio_innertube.$audioExt"

        // Shared progress counters
        val videoDl = AtomicLong(0)
        val audioDl = AtomicLong(0)
        val videoTotal = AtomicLong(if (videoStream.size > 0) videoStream.size else 0)
        val audioTotal = AtomicLong(if (audioStream.size > 0) audioStream.size else 0)
        val lastProgress = AtomicLong(0)

        val reportProgress = {
            val now = System.currentTimeMillis()
            if (now - lastProgress.get() >= PROGRESS_INTERVAL_MS) {
                lastProgress.set(now)
                val total = videoTotal.get() + audioTotal.get()
                if (total > 0) {
                    val frac = ((videoDl.get() + audioDl.get()).toDouble() / total).coerceIn(0.0, 1.0)
                    progressCallback(frac * 0.85, "Downloading…")
                }
            }
        }

        val latch = CountDownLatch(2)
        val videoError = AtomicReference<Exception?>(null)
        val audioError = AtomicReference<Exception?>(null)

        Thread({
            try {
                downloadChunkedOrSingle(videoStream.url, tempVideoPath, videoStream.size) { b, t ->
                    videoDl.set(b); if (t > 0) videoTotal.set(t); reportProgress()
                }
            } catch (e: Exception) { videoError.set(e) }
            finally { latch.countDown() }
        }, "innertube-video-dl").start()

        Thread({
            try {
                downloadChunkedOrSingle(audioStream.url, tempAudioPath, audioStream.size) { b, t ->
                    audioDl.set(b); if (t > 0) audioTotal.set(t); reportProgress()
                }
            } catch (e: Exception) { audioError.set(e) }
            finally { latch.countDown() }
        }, "innertube-audio-dl").start()

        latch.await()
        videoError.get()?.let { throw Exception("Video download failed: ${it.message}", it) }
        audioError.get()?.let { throw Exception("Audio download failed: ${it.message}", it) }

        return Pair(tempVideoPath, tempAudioPath)
    }

    fun sanitizeFilename(name: String) =
        name.replace(Regex("[<>:\"/\\\\|?*]"), "_").replace(Regex("\\s+"), "_")

    // ── Innertube fetch ──────────────────────────────────────────────────────

    private fun fetchPlayerResponse(videoId: String, clientType: String): StreamBundle {
        val (clientName, clientVersion, clientId, userAgent) = when (clientType) {
            "TV"  -> arrayOf(TV_CLIENT_NAME, TV_CLIENT_VERSION, TV_CLIENT_ID, TV_UA)
            "IOS" -> arrayOf(IOS_CLIENT_NAME, IOS_CLIENT_VERSION, IOS_CLIENT_ID, IOS_UA)
            else  -> arrayOf(ANDROID_CLIENT_NAME, ANDROID_CLIENT_VERSION, ANDROID_CLIENT_ID, ANDROID_UA)
        }

        val clientContext = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            put("timeZone", "UTC")
            when (clientType) {
                "ANDROID" -> {
                    put("androidSdkVersion", ANDROID_SDK)
                    put("osName", "Android")
                    put("osVersion", "11")
                    put("platform", "MOBILE")
                    put("userAgent", userAgent)
                }
                "IOS" -> {
                    put("osName", "iPhone")
                    put("osVersion", "18.2.1")
                    put("deviceModel", "iPhone16,2")
                    put("platform", "MOBILE")
                }
            }
        }

        val body = JSONObject().apply {
            put("context", JSONObject().apply { put("client", clientContext) })
            put("videoId", videoId)
            if (clientType == "ANDROID") {
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }
        }.toString()

        val req = Request.Builder()
            .url(PLAYER_ENDPOINT)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", userAgent)
            .header("X-Youtube-Client-Name", clientId)
            .header("X-Youtube-Client-Version", clientVersion)
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.youtube.com")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val resp = apiClient.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("Player API HTTP ${resp.code}")

        val json = JSONObject(resp.body!!.string())

        val playStatus = json.optJSONObject("playabilityStatus")?.optString("status")
        if (playStatus != "OK") {
            val reason = json.optJSONObject("playabilityStatus")?.optString("reason") ?: playStatus
            throw Exception("Video unavailable: $reason")
        }

        val title = json.optJSONObject("videoDetails")?.optString("title") ?: "Unknown"
        val streaming = json.optJSONObject("streamingData")
            ?: throw Exception("No streamingData in player response")
        val formats = streaming.optJSONArray("adaptiveFormats")
            ?: throw Exception("No adaptiveFormats in player response")

        val audioStreams = mutableListOf<AudioStream>()
        val videoStreams = mutableListOf<VideoStream>()

        for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            val directUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue

            val rawMime = f.optString("mimeType", "")
            val mime = rawMime.substringBefore(";").trim()
            val codec = rawMime.substringAfter("codecs=\"", "").substringBefore("\"")
            val bitrate = f.optInt("averageBitrate", f.optInt("bitrate", 0))
            val size = f.optString("contentLength", "0").toLongOrNull() ?: 0L

            when {
                mime.startsWith("audio/") -> audioStreams.add(
                    AudioStream(directUrl, mime, codec, bitrate, size)
                )
                mime.startsWith("video/") -> videoStreams.add(
                    VideoStream(
                        url = directUrl, mimeType = mime, codec = codec,
                        bitrate = bitrate, size = size,
                        width = f.optInt("width", 0),
                        height = f.optInt("height", 0),
                        fps = f.optInt("fps", 30),
                        qualityLabel = f.optString("qualityLabel", "${f.optInt("height", 0)}p")
                    )
                )
            }
        }

        Log.d(TAG, "Innertube($videoId): ${audioStreams.size} audio, ${videoStreams.size} video direct-URL streams")
        return StreamBundle(videoId, title, audioStreams, videoStreams)
    }

    // ── Download helpers ─────────────────────────────────────────────────────

    private fun downloadChunkedOrSingle(
        url: String,
        outputPath: String,
        expectedSize: Long,
        progress: (Long, Long) -> Unit
    ) {
        val out = File(outputPath).also { it.parentFile?.mkdirs() }
        val contentLength = resolveLength(url, expectedSize)

        if (contentLength > MIN_CHUNK_SIZE) {
            try { downloadChunked(url, out, contentLength, progress); return }
            catch (_: RangeFallback) { /* fall through */ }
        }
        downloadSingle(url, out, contentLength, progress)
    }

    private class RangeFallback : Exception()

    private fun downloadChunked(
        url: String, out: File, size: Long, progress: (Long, Long) -> Unit
    ) {
        val count = chunkCount(size)
        val chunkSize = size / count
        val downloaded = AtomicLong(0)

        RandomAccessFile(out, "rw").use { it.setLength(size) }

        val latch = CountDownLatch(count)
        val firstErr = AtomicReference<Exception?>(null)

        for (i in 0 until count) {
            val start = i * chunkSize
            val end = if (i == count - 1) size - 1 else start + chunkSize - 1
            chunkPool.submit {
                try {
                    if (firstErr.get() != null) return@submit
                    val req = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
                    val resp = dlClient.newCall(req).execute()
                    if (resp.code == 200) { resp.close(); throw RangeFallback() }
                    if (!resp.isSuccessful && resp.code != 206) throw Exception("Chunk $i: HTTP ${resp.code}")

                    val body = resp.body ?: throw Exception("Empty body chunk $i")
                    val buf = ByteArray(BUFFER_SIZE)
                    val bbuf = ByteBuffer.wrap(buf)
                    var local = 0L
                    body.byteStream().use { input ->
                        RandomAccessFile(out, "rw").use { raf ->
                            val ch = raf.channel; var pos = start
                            while (pos <= end) {
                                val n = input.read(buf); if (n == -1) break
                                bbuf.clear(); bbuf.limit(n); ch.write(bbuf, pos)
                                pos += n; local += n
                                if (local >= 1L * 1024 * 1024) {
                                    downloaded.addAndGet(local); local = 0
                                    progress(downloaded.get(), size)
                                }
                            }
                        }
                    }
                    if (local > 0) { downloaded.addAndGet(local); progress(downloaded.get(), size) }
                } catch (e: RangeFallback) { firstErr.compareAndSet(null, e) }
                catch (e: Exception) { firstErr.compareAndSet(null, e) }
                finally { latch.countDown() }
            }
        }
        latch.await()
        firstErr.get()?.let { throw it }
    }

    private fun downloadSingle(
        url: String, out: File, expectedSize: Long, progress: (Long, Long) -> Unit
    ) {
        val resp = dlClient.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        val body = resp.body ?: throw Exception("Empty response body")
        val total = if (expectedSize > 0) expectedSize else body.contentLength()
        var dl = 0L
        val buf = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            BufferedOutputStream(FileOutputStream(out), BUFFER_SIZE).use { output ->
                while (true) {
                    val n = input.read(buf); if (n == -1) break
                    output.write(buf, 0, n); dl += n; progress(dl, total)
                }
            }
        }
    }

    private fun resolveLength(url: String, expected: Long): Long {
        if (expected > 0) return expected
        return try {
            dlClient.newCall(Request.Builder().url(url).head().build()).execute()
                .use { it.header("Content-Length")?.toLongOrNull() ?: -1L }
        } catch (_: Exception) { -1L }
    }
}
