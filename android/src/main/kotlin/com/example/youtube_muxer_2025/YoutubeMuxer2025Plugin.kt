package com.example.youtube_muxer_2025

import android.content.Context
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.util.concurrent.Executors

class YoutubeMuxer2025Plugin : FlutterPlugin, MethodCallHandler {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var context: Context
    private var eventSink: EventChannel.EventSink? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val extractorService = YouTubeExtractorService()

    companion object {
        private const val TAG = "YoutubeMuxer2025Plugin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "youtube_muxer_2025")
        channel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "youtube_muxer_2025/progress")
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "muxVideoAndAudio" -> {
                val videoPath = call.argument<String>("videoPath")
                val audioPath = call.argument<String>("audioPath")
                val outputPath = call.argument<String>("outputPath")

                if (videoPath != null && audioPath != null && outputPath != null) {
                    val success = MediaMuxerHandler().muxVideoAndAudio(videoPath, audioPath, outputPath)
                    result.success(success)
                } else {
                    result.error("INVALID_ARGUMENTS", "Missing parameters for muxing", null)
                }
            }

            "getQualities" -> {
                val url = call.argument<String>("url")
                if (url == null) {
                    result.error("INVALID_ARGUMENTS", "Missing 'url' parameter", null)
                    return
                }

                executor.execute {
                    try {
                        val qualities = extractorService.getQualities(url)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(qualities)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "getQualities failed", e)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.error("EXTRACTION_ERROR", e.message ?: "Unknown error", null)
                        }
                    }
                }
            }

            "downloadVideo" -> {
                val url = call.argument<String>("url")
                val quality = call.argument<String>("quality")

                if (url == null || quality == null) {
                    result.error("INVALID_ARGUMENTS", "Missing 'url' or 'quality' parameter", null)
                    return
                }

                executor.execute {
                    try {
                        val tempDir = context.cacheDir.absolutePath
                        val title = extractorService.getVideoTitle(url)
                        val safeTitle = extractorService.sanitizeFilename(title)

                        val documentsDir = File(context.filesDir, "downloads")
                        documentsDir.mkdirs()
                        val outputPath = "${documentsDir.absolutePath}/$safeTitle.mp4"

                        // Download streams with progress
                        val (tempVideoPath, tempAudioPath) = extractorService.downloadStreams(
                            url, quality, tempDir
                        ) { progress, status ->
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            mainHandler.post {
                                eventSink?.success(mapOf(
                                    "progress" to progress,
                                    "status" to status,
                                    "title" to title
                                ))
                            }
                        }

                        // Mux (85% - 100%)
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        mainHandler.post {
                            eventSink?.success(mapOf(
                                "progress" to 0.85,
                                "status" to "Muxing files...",
                                "title" to title
                            ))
                        }

                        val success = MediaMuxerHandler().muxVideoAndAudio(
                            tempVideoPath, tempAudioPath, outputPath
                        )

                        // Clean up temp files
                        try { File(tempVideoPath).delete() } catch (_: Exception) {}
                        try { File(tempAudioPath).delete() } catch (_: Exception) {}

                        if (!success) {
                            throw Exception("Muxing failed")
                        }

                        mainHandler.post {
                            eventSink?.success(mapOf(
                                "progress" to 1.0,
                                "status" to "Download completed",
                                "outputPath" to outputPath,
                                "title" to title
                            ))
                        }

                        mainHandler.post {
                            result.success(mapOf(
                                "success" to true,
                                "outputPath" to outputPath,
                                "title" to title
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "downloadVideo failed", e)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.error("DOWNLOAD_ERROR", e.message ?: "Unknown error", null)
                        }
                    }
                }
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }
}
