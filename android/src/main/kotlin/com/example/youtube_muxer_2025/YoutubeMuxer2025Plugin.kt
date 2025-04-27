package com.example.youtube_muxer_2025

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class YoutubeMuxer2025Plugin: FlutterPlugin, MethodCallHandler {
  private lateinit var channel : MethodChannel

  override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "youtube_muxer_2025")
    channel.setMethodCallHandler(this)
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
      else -> result.notImplemented()
    }
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }
}
