# youtube_muxer_2025

A Flutter plugin to mux (merge) video and audio files into one video file.

## Features
- Download and mux YouTube videos
- Support for various video qualities
- Android platform support
- Permission handling

## Installation
```yaml
dependencies:
  youtube_muxer_2025: ^0.0.3
```

## Usage
```dart
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';

// Example code showing basic usage
final downloader = YoutubeDownloader();
final qualities = await downloader.getQualities('VIDEO_URL');
```

## Additional information
- Supported platforms: Android
- Required permissions: Storage, Internet

