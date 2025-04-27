class DownloadProgress {
  final double progress;
  final int estimatedTimeRemaining;
  final String status;
  final String? outputPath;
  final String? title;

  DownloadProgress({
    required this.progress,
    required this.estimatedTimeRemaining,
    required this.status,
    this.outputPath,
    this.title,
  });

  @override
  String toString() {
    return 'DownloadProgress(progress: ${progress * 100}%, status: $status)';
  }
}
