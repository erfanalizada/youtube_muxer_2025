package com.example.youtube_muxer_2025

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class MediaMuxerHandler {

    fun muxVideoAndAudio(videoPath: String, audioPath: String, outputPath: String): Boolean {
        return mux(
            videoExtractor = createExtractor(videoPath),
            audioExtractor = createExtractor(audioPath),
            outputPath = outputPath
        )
    }

    fun muxVideoAndAudioBytes(videoBytes: ByteArray, audioBytes: ByteArray, outputPath: String): Boolean {
        val videoExtractor = createExtractorFromBytes(videoBytes)
        val audioExtractor = createExtractorFromBytes(audioBytes)
        return mux(videoExtractor, audioExtractor, outputPath)
    }

    fun muxSingleVideoBytes(videoBytes: ByteArray, outputPath: String): Boolean {
        return try {
            val file = File(outputPath)
            file.writeBytes(videoBytes)
            true
        } catch (e: Exception) {
            Log.e("MediaMuxerHandler", "Saving single video bytes failed: ${e.message}", e)
            false
        }
    }

    fun muxSingleVideoFile(videoPath: String, outputPath: String): Boolean {
        return try {
            val sourceFile = File(videoPath)
            val destFile = File(outputPath)
            sourceFile.copyTo(destFile, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e("MediaMuxerHandler", "Saving single video file failed: ${e.message}", e)
            false
        }
    }

    private fun mux(videoExtractor: MediaExtractor, audioExtractor: MediaExtractor, outputPath: String): Boolean {
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            val videoTrackIndex = selectTrack(videoExtractor, isVideo = true)
            val audioTrackIndex = selectTrack(audioExtractor, isVideo = false)

            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                throw RuntimeException("Missing video or audio track")
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val muxerVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val muxerAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))

            muxer.start()
            muxerStarted = true

            writeSamples(videoExtractor, muxer, muxerVideoTrack)
            writeSamples(audioExtractor, muxer, muxerAudioTrack)

            return true
        } catch (e: Exception) {
            Log.e("MediaMuxerHandler", "Muxing failed: ${e.message}", e)
            return false
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }

    private fun createExtractor(filePath: String): MediaExtractor {
        return MediaExtractor().apply { setDataSource(filePath) }
    }

    private fun createExtractorFromBytes(bytes: ByteArray): MediaExtractor {
        val tempFile = File.createTempFile("temp_", ".mp4")
        tempFile.writeBytes(bytes)
        return MediaExtractor().apply { setDataSource(tempFile.absolutePath) }
    }

    private fun selectTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != null) {
                if (isVideo && mime.startsWith("video/")) return i
                if (!isVideo && mime.startsWith("audio/")) return i
            }
        }
        return -1
    }

    private fun writeSamples(extractor: MediaExtractor, muxer: MediaMuxer, trackIndex: Int) {
        val buffer = ByteBuffer.allocate(1 * 1024 * 1024) // 1MB buffer
        val info = MediaCodec.BufferInfo()

        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.offset = 0
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }
}
