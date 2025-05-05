/// Represents a video quality option available for download.
///
/// This class contains information about a specific video quality option,
/// including resolution, codec, container format, and file size.
class VideoQuality {
  /// The quality label (e.g., '1080p', '720p')
  final String quality; // Example: '1080p'
  /// Direct video stream URL for downloading
  final String url; // Direct video stream URL
  /// File size in bytes
  final int size; // File size in bytes
  /// Container format (e.g., mp4, webm)
  final String container; // mp4, webm, etc
  /// Video codec information (e.g., H264, VP9)
  final String codec; // Codec info (H264, VP9, etc)
  /// Bitrate in bits per second
  final int bitrate; // Bitrate in bits per second
  /// Frames per second
  final int fps; // Frames per second

  /// Creates a new video quality option.
  ///
  /// All parameters are required.
  VideoQuality({
    required this.quality,
    required this.url,
    required this.size,
    required this.container,
    required this.codec,
    required this.bitrate,
    required this.fps,
  });

  @override
  String toString() {
    return 'VideoQuality('
        'quality: $quality, '
        'codec: $codec, '
        'fps: ${fps}fps, '
        'size: ${(size / 1024 / 1024).toStringAsFixed(2)}MB)';
  }
}
