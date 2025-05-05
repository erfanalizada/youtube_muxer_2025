/// Represents the current progress of a YouTube video download operation.
///
/// This class provides information about the download progress, including
/// the completion percentage, current status message, estimated time remaining,
/// and the final output path when the download is complete.
class DownloadProgress {
  /// The current progress of the download as a value between 0.0 and 1.0.
  final double progress;
  
  /// A descriptive status message about the current download state.
  final String status;
  
  /// The final path where the downloaded video is saved.
  /// This value is null until the download is complete.
  final String? outputPath;
  
  /// Estimated time remaining for the download to complete, in seconds.
  /// This value may be null if the time cannot be estimated.
  final int? estimatedTimeRemaining;
  
  /// The title of the video being downloaded.
  final String? title;

  /// Creates a new download progress instance.
  const DownloadProgress({
    required this.progress,
    required this.status,
    this.outputPath,
    this.estimatedTimeRemaining,
    this.title,
  });
}
