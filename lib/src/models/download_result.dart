class DownloadResult {
  final String filePath; // Final path where file is saved
  final String title; // Video title or custom name
  final bool success; // True if download succeeded

  DownloadResult({
    required this.filePath,
    required this.title,
    required this.success,
  });

  @override
  String toString() {
    return 'DownloadResult(filePath: $filePath, success: $success)';
  }
}
