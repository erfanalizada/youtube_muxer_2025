import 'dart:async';
import 'package:flutter/services.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart' as models;
import 'models/download_progress.dart';

/// Main class for downloading and processing YouTube videos.
///
/// This class provides methods to fetch available video qualities and
/// download videos from YouTube with progress tracking.
///
/// All YouTube extraction is handled natively on Android via NewPipe Extractor.
class YoutubeDownloader {
  static const MethodChannel platform = MethodChannel('youtube_muxer_2025');
  static const EventChannel _progressChannel =
      EventChannel('youtube_muxer_2025/progress');

  /// Creates a new YouTube downloader instance.
  YoutubeDownloader();

  /// Retrieves all available quality options for a YouTube video.
  ///
  /// [videoUrl] is the full YouTube video URL.
  ///
  /// Returns a list of [models.VideoQuality] objects representing the available
  /// quality options for the video.
  Future<List<models.VideoQuality>> getQualities(String videoUrl) async {
    try {
      final List<dynamic> result = await platform.invokeMethod(
        'getQualities',
        {'url': videoUrl},
      );

      return result.map((item) {
        final map = Map<String, dynamic>.from(item as Map);
        return models.VideoQuality(
          quality: map['quality'] as String,
          url: map['url'] as String,
          size: (map['size'] as num).toInt(),
          container: map['container'] as String,
          codec: map['codec'] as String,
          bitrate: (map['bitrate'] as num).toInt(),
          fps: (map['fps'] as num).toInt(),
        );
      }).toList();
    } on PlatformException catch (e) {
      throw Exception('Failed to get video qualities: ${e.message}');
    } catch (e) {
      throw Exception('Failed to get video qualities: $e');
    }
  }

  /// Downloads the best available audio stream for [videoUrl].
  ///
  /// No quality selection needed — the native side always picks the
  /// highest-bitrate MP4/M4A audio stream automatically.
  ///
  /// Returns a stream of [DownloadProgress] objects. The final event has
  /// [DownloadProgress.outputPath] set to the saved `.m4a` file path.
  Stream<DownloadProgress> downloadAudio(String videoUrl) async* {
    final progressController = StreamController<DownloadProgress>();

    StreamSubscription? progressSubscription;
    progressSubscription = _progressChannel
        .receiveBroadcastStream()
        .listen((event) {
      if (event is Map) {
        final map = Map<String, dynamic>.from(event);
        progressController.add(DownloadProgress(
          progress: (map['progress'] as num).toDouble(),
          status: map['status'] as String,
          outputPath: map['outputPath'] as String?,
          title: map['title'] as String?,
          estimatedTimeRemaining: 0,
        ));
      }
    });

    try {
      final resultFuture = platform.invokeMethod('downloadAudio', {'url': videoUrl});

      // If the native call fails, propagate the error into the stream so
      // the await-for below doesn't hang waiting for events that never arrive.
      resultFuture.then((_) {
        if (!progressController.isClosed) progressController.close();
      }, onError: (e) {
        if (!progressController.isClosed) {
          progressController.addError(e);
          progressController.close();
        }
      });

      await for (final progress in progressController.stream) {
        yield progress;
        // Only stop when the native side confirms the output path — progress
        // can legitimately reach 1.0 during the download phase (last batch of
        // bytes) before the "completed" event (which carries outputPath) fires.
        if (progress.outputPath != null) break;
      }
    } on PlatformException catch (e) {
      throw Exception('Audio download failed: ${e.message}');
    } catch (e) {
      throw Exception('Audio download failed: $e');
    } finally {
      await progressSubscription.cancel();
      if (!progressController.isClosed) await progressController.close();
    }
  }

  /// Downloads a YouTube video in the specified quality.
  ///
  /// [quality] is the selected [models.VideoQuality] option from [getQualities].
  /// [videoUrl] is the full YouTube video URL.
  ///
  /// Returns a stream of [DownloadProgress] objects that provide real-time
  /// updates about the download progress.
  Stream<DownloadProgress> downloadVideo(
    models.VideoQuality quality,
    String videoUrl,
  ) async* {
    final progressController = StreamController<DownloadProgress>();

    // Listen to progress events from native side
    StreamSubscription? progressSubscription;
    progressSubscription = _progressChannel
        .receiveBroadcastStream()
        .listen((event) {
      if (event is Map) {
        final map = Map<String, dynamic>.from(event);
        progressController.add(DownloadProgress(
          progress: (map['progress'] as num).toDouble(),
          status: map['status'] as String,
          outputPath: map['outputPath'] as String?,
          title: map['title'] as String?,
          estimatedTimeRemaining: 0,
        ));
      }
    });

    try {
      final resultFuture = platform.invokeMethod(
        'downloadVideo',
        {
          'url': videoUrl,
          'quality': quality.quality,
        },
      );

      // If the native call fails, propagate the error into the stream so
      // the await-for below doesn't hang waiting for events that never arrive.
      resultFuture.then((_) {
        if (!progressController.isClosed) progressController.close();
      }, onError: (e) {
        if (!progressController.isClosed) {
          progressController.addError(e);
          progressController.close();
        }
      });

      await for (final progress in progressController.stream) {
        yield progress;
        if (progress.progress >= 1.0) break;
      }
    } on PlatformException catch (e) {
      throw Exception('Download failed: ${e.message}');
    } catch (e) {
      throw Exception('Download failed: $e');
    } finally {
      await progressSubscription.cancel();
      if (!progressController.isClosed) await progressController.close();
    }
  }
}
