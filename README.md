# YouTube Muxer 2025

A powerful Flutter plugin for downloading and muxing YouTube videos with various quality options.


# Support

If you find this package helpful, consider supporting my work:

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-%23FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/erfan1)

[![Pub Version](https://img.shields.io/pub/v/youtube_muxer_2025.svg)](https://pub.dev/packages/youtube_muxer_2025)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## Important Notice

### YouTube Terms of Service & Copyright

This package is intended for personal and educational use only. Before using this package, please be aware:

- Respect YouTube's Terms of Service
- Only download videos you have permission to download
- Some videos may be protected by copyright
- Commercial use may be restricted
- YouTube's terms of service may prohibit unauthorized downloading

Users are responsible for ensuring their use of this package complies with YouTube's terms of service and applicable copyright laws. The authors of this package are not responsible for misuse or any resulting legal consequences.

### Fair Use and Legal Considerations

- Always check your local laws regarding content downloading
- Obtain necessary permissions before downloading copyrighted content
- Consider using YouTube's official APIs for commercial applications
- Respect content creators' rights and intellectual property

This package is provided "as is" without any guarantees regarding compliance with YouTube's terms of service or other legal requirements.

## Features

- Download YouTube videos in various qualities
- **Multi-connection chunked downloading** (8 parallel connections per file) for maximum speed
- **Parallel video + audio downloads** — both streams download simultaneously
- Mux (merge) video and audio streams using Native Android MediaMuxer
- Platform-specific permission handling
- Real-time download progress tracking with throttled updates
- Secure downloading with proper error handling
- Returns final video file path for further processing
- Uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) for reliable YouTube extraction on native Android
- Automatic fallback to single-connection download when Range requests are not supported

## How It Works

The plugin uses **NewPipe Extractor** on the native Android side to extract video/audio stream URLs from YouTube. Downloads are accelerated using **multi-connection chunked downloading** — each file is split into 8 chunks downloaded in parallel via HTTP Range headers, bypassing per-connection throttling. Video and audio streams download simultaneously on dedicated threads. Once both are complete, they are muxed together using Android's native **MediaMuxer**. The Dart side is a thin method-channel client — all heavy lifting happens natively.

### Download Architecture

```
                        downloadStreams()
                       /                \
              [Thread: video]      [Thread: audio]
              /   |   ...  \       /   |   ...  \
          chunk1 chunk2 ... chunk8  chunk1 ... chunk8
            |      |          |       |           |
          (8 parallel HTTP connections per file)
```

- **8 parallel connections** per file using HTTP Range headers
- **512 KB buffers** per connection for efficient I/O
- **OkHttp** with 32-connection pool, HTTP/1.1 forced for separate TCP per connection
- **Progress throttled** to 150ms intervals to avoid flooding Flutter
- **Graceful fallback** to single buffered connection if Range not supported or file < 1 MB

Perfect for:
- Saving videos to device storage
- Processing with your own video player
- Integrating with other media handling systems
- Custom video management solutions
- Mp4 players


## Installation

Add this to your package's `pubspec.yaml` file:

```yaml
dependencies:
  youtube_muxer_2025: ^0.2.0
  video_player: ^2.7.2
  permission_handler: ^12.0.0+1
  device_info_plus: ^11.4.0
```

### Android Setup — JitPack Repository

This plugin uses [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) which is hosted on JitPack. You **must** add the JitPack repository to your app's `android/build.gradle` (Groovy) or `android/build.gradle.kts` (Kotlin DSL):

**Groovy** (`android/build.gradle`):
```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // Required for youtube_muxer_2025
    }
}
```

**Kotlin DSL** (`android/build.gradle.kts`):
```kotlin
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // Required for youtube_muxer_2025
    }
}
```

## Getting Started

### Basic Usage

```dart
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';

// Create an instance of YoutubeDownloader
final downloader = YoutubeDownloader();

// Get available qualities
final qualities = await downloader.getQualities('VIDEO_URL');

// Download video
await for (final progress in downloader.downloadVideo(quality, 'VIDEO_URL')) {
  print('Progress: ${progress.progress * 100}%');
  print('Status: ${progress.status}');

  if (progress.outputPath != null) {
    print('Downloaded to: ${progress.outputPath}');
  }
}
```

### Permission Handling

```dart
// Check storage permission
final permission = await Permission.storage.request();
if (permission.isGranted) {
  // Proceed with download
} else {
  // Handle permission denied
}
```

## Platform Support

| Android | iOS | macOS | Web | Linux | Windows |
|---------|-----|-------|-----|--------|---------|
| Yes     | WIP | No    | No  | No     | No      |

## Required Permissions

### Android

Add these permissions to your `android/app/src/main/AndroidManifest.xml`:

```xml
<!-- For Android 12 and below -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>

<!-- For Android 13 and above -->
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO"/>
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
```

### Crucial Manifest Configuration

It is **essential** to add the following configuration to your Android manifest to prevent build errors:

![Manifest Configuration](https://raw.githubusercontent.com/erfanalizada/youtube_muxer_2025/main/Manifest-Config.png)

Without this configuration, the app will fail to build properly. Make sure to include `tools:replace="android:label"` in your manifest as shown in the image above.

## Complete Example

Here's a complete example showing how to implement a YouTube downloader interface:

```dart
import 'package:flutter/material.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

// Basic Flutter app setup
class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'YouTube Muxer 2025 Example',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const YouTubeDownloaderScreen(),
    );
  }
}

// Main downloader screen
class YouTubeDownloaderScreen extends StatefulWidget {
  const YouTubeDownloaderScreen({super.key});

  @override
  State<YouTubeDownloaderScreen> createState() => _YouTubeDownloaderScreenState();
}

class _YouTubeDownloaderScreenState extends State<YouTubeDownloaderScreen> {
  // Controllers and state variables
  final _urlController = TextEditingController();  // For URL input
  final _downloader = YoutubeDownloader();         // Main downloader instance
  List<VideoQuality> _qualities = [];              // Available video qualities
  bool _isLoading = false;                        // Loading state
  String _status = '';                            // Current status message
  double _progress = 0.0;                         // Download progress (0.0 to 1.0)
  String? _downloadedPath;                        // Path to downloaded file

  @override
  void initState() {
    super.initState();
    _checkPermissions();  // Check storage permissions on startup
  }

  // Request storage permissions
  Future<void> _checkPermissions() async {
    await Permission.storage.request();
  }

  // Fetch available video qualities
  Future<void> _getQualities() async {
    if (_urlController.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enter a YouTube URL')),
      );
      return;
    }

    setState(() {
      _isLoading = true;
      _status = 'Fetching qualities...';
      _qualities = [];
    });

    try {
      final qualities = await _downloader.getQualities(_urlController.text);
      setState(() {
        _qualities = qualities;
        _status = 'Select quality to download';
      });
    } catch (e) {
      setState(() => _status = 'Error: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Failed to get qualities: $e')),
      );
    } finally {
      setState(() => _isLoading = false);
    }
  }

  // Download video in selected quality
  Future<void> _downloadVideo(VideoQuality quality) async {
    try {
      await for (final progress in _downloader.downloadVideo(
        quality,
        _urlController.text,
      )) {
        if (!mounted) return;

        setState(() {
          _progress = progress.progress;
          _status = progress.status;
        });

        if (progress.progress == 1.0 && progress.outputPath != null) {
          setState(() => _downloadedPath = progress.outputPath);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Download complete!\nSaved to: ${progress.outputPath}'),
              duration: const Duration(seconds: 5),
            ),
          );
        }
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'Error: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Download failed: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('YouTube Downloader'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // URL input field
            TextField(
              controller: _urlController,
              decoration: const InputDecoration(
                labelText: 'YouTube URL',
                hintText: 'Enter YouTube video URL',
              ),
            ),
            const SizedBox(height: 16),

            // Get qualities button
            ElevatedButton(
              onPressed: _isLoading ? null : _getQualities,
              child: Text(_isLoading ? 'Loading...' : 'Get Qualities'),
            ),
            const SizedBox(height: 16),

            // Quality selection list
            if (_qualities.isNotEmpty) ...[
              Text('Available Qualities:'),
              Expanded(
                child: ListView.builder(
                  itemCount: _qualities.length,
                  itemBuilder: (context, index) {
                    final quality = _qualities[index];
                    return ListTile(
                      title: Text(quality.quality),
                      subtitle: Text('Size: ${(quality.size / 1024 / 1024).toStringAsFixed(2)} MB'),
                      onTap: () => _downloadVideo(quality),
                    );
                  },
                ),
              ),
            ],

            // Progress indicators
            if (_progress > 0) ...[
              const SizedBox(height: 16),
              LinearProgressIndicator(value: _progress),
              Text('Progress: ${(_progress * 100).toStringAsFixed(1)}%'),
              Text('Status: $_status'),
            ],

            // Downloaded file path
            if (_downloadedPath != null) ...[
              const SizedBox(height: 16),
              Text('Last downloaded file:',
                   style: Theme.of(context).textTheme.titleSmall),
              Text(_downloadedPath!,
                   style: const TextStyle(fontFamily: 'monospace')),
            ],
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _urlController.dispose();
    super.dispose();
  }
}
```

### Code Breakdown

1. **Setup & Initialization**
   - Creates a basic Flutter app with Material design
   - Initializes the downloader and necessary controllers
   - Checks for storage permissions, for instance on startup or on button press before calling the downloader.

2. **URL Input & Quality Fetching**
   - Example implementation of URL input using Flutter's TextField
   - Demonstrates how to fetch available video qualities using `getQualities()`
   - Shows proper handling of loading states and error messages

3. **Quality Selection**
   - Shows a list of available qualities with file sizes
   - Displays quality information in a clear format

4. **Download Progress**
   - Provides download progress through a Stream
   - Returns `DownloadProgress` objects containing:
     - Progress percentage (0.0 to 1.0)
     - Status messages
     - Final output path when complete
   - Example shows how to implement progress UI using Flutter widgets

5. **Output Path**
   - Returns the final downloaded file path through `DownloadProgress.outputPath`
   - Path is provided once download is complete
   - Can be used for further file processing or display

6. **Error Handling**
   - Throws detailed exceptions for:
     - Network failures
     - Invalid video URLs
     - Unsupported video formats
     - Muxing failures
   - Provides error information through the download stream
   - Example shows proper error handling and user feedback implementation

## Advanced Usage

### Quality Selection

```dart
// Get all available qualities
final qualities = await downloader.getQualities(videoUrl);

// Filter for HD qualities
final hdQualities = qualities.where((q) =>
  int.parse(q.quality.replaceAll(RegExp(r'[^0-9]'), '')) >= 720
).toList();
```

## Progress Tracking

The download progress provides detailed, real-time information streamed from the native side:

```dart
class DownloadProgress {
  final double progress;     // 0.0 to 1.0
  final String status;      // Current status message
  final String? outputPath; // Final video path when complete
  final String? title;      // Video title
}
```

Progress phases:
- **0% - 85%**: Downloading video + audio streams (in parallel)
- **85% - 100%**: Muxing video + audio into final MP4

## Contributing

Contributions are welcome! Here's how you can help:

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Bug Reports and Feature Requests

Please use the [GitHub Issues](https://github.com/erfanalizada/youtube_muxer_2025/issues) page to report any bugs or file feature requests.

## Show Your Support

If you find this project helpful, please give it a star on GitHub! It helps the project grow and improve.

## Contact

Email: erfanalizada6@gmail.com
Project Link: [https://github.com/erfanalizada/youtube_muxer_2025](https://github.com/erfanalizada/youtube_muxer_2025)
