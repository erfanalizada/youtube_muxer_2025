package com.example.youtube_muxer_2025

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class YouTubeExtractorService {

    companion object {
        private const val TAG = "YouTubeExtractorService"
        private var initialized = false

        fun ensureInitialized() {
            if (!initialized) {
                NewPipe.init(NewPipeDownloaderImpl.instance)
                initialized = true
            }
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun getQualities(url: String): List<Map<String, Any>> {
        ensureInitialized()

        val streamInfo = StreamInfo.getInfo(
            ServiceList.YouTube,
            url
        )

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

        // Sort highest resolution first
        qualities.sortByDescending { map ->
            val q = map["quality"] as String
            q.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
        }

        return qualities
    }

    fun downloadStreams(
        url: String,
        qualityLabel: String,
        tempDir: String,
        progressCallback: (Double, String) -> Unit
    ): Pair<String, String> {
        ensureInitialized()

        val streamInfo = StreamInfo.getInfo(
            ServiceList.YouTube,
            url
        )

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

        // Download video (0% - 45%)
        progressCallback(0.0, "Downloading video...")
        downloadFile(
            videoStream.content,
            tempVideoPath,
            videoStream.itagItem?.contentLength ?: -1L
        ) { fraction ->
            progressCallback(fraction * 0.45, "Downloading video...")
        }

        // Download audio (45% - 85%)
        progressCallback(0.45, "Downloading audio...")
        downloadFile(
            audioStream.content,
            tempAudioPath,
            audioStream.itagItem?.contentLength ?: -1L
        ) { fraction ->
            progressCallback(0.45 + fraction * 0.40, "Downloading audio...")
        }

        return Pair(tempVideoPath, tempAudioPath)
    }

    fun getVideoTitle(url: String): String {
        ensureInitialized()
        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, url)
        return streamInfo.name ?: "video"
    }

    private fun downloadFile(
        url: String,
        outputPath: String,
        expectedSize: Long,
        progressCallback: (Double) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Download failed with HTTP ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val totalBytes = if (expectedSize > 0) expectedSize else body.contentLength()
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        var downloadedBytes = 0L
        val buffer = ByteArray(8192)

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        progressCallback(downloadedBytes.toDouble() / totalBytes.toDouble())
                    }
                }
            }
        }

        Log.d(TAG, "Downloaded ${downloadedBytes} bytes to $outputPath")
    }

    fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), "_")
    }
}
