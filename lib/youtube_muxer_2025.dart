/// A Flutter plugin for downloading and muxing YouTube videos with various quality options.
///
/// This package allows you to download YouTube videos in different qualities,
/// track download progress, and mux video and audio streams using native platform capabilities.
///
/// Example usage:
/// ```dart
/// final downloader = YoutubeDownloader();
/// final qualities = await downloader.getQualities('VIDEO_URL');
/// await for (final progress in downloader.downloadVideo(quality, 'VIDEO_URL')) {
///   print('Progress: ${progress.progress * 100}%');
///   print('Status: ${progress.status}');
///
///   if (progress.outputPath != null) {
///     print('Downloaded to: ${progress.outputPath}');
///   }
/// }
/// ```
library;

export 'src/youtube_downloader.dart';
export 'src/models/download_progress.dart';
export 'src/models/video_quality.dart';
