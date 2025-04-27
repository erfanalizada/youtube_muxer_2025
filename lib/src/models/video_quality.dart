class VideoQuality {
  final String quality;      // Example: '1080p'
  final String url;          // Direct video stream URL
  final int size;            // File size in bytes
  final String container;    // mp4, webm, etc
  final String codec;        // Codec info (H264, VP9, etc)
  final int bitrate;         // Bitrate in bits per second
  final int fps;             // Frames per second

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
