
## 0.3.0

* Dynamic chunk count: 16 connections for 50 MB+ files, 12 for 10 MB+, 8 for smaller files
* Eliminated Range probe request — saves ~200-400ms latency per download (YouTube CDN always supports Range; falls back gracefully if not)
* FileChannel positional writes instead of RandomAccessFile seek+write for better concurrent I/O
* Batched progress updates (1 MB intervals per thread) to reduce atomic contention across threads
* Connection pool increased to 40, thread pool to 34 to support higher parallelism

## 0.2.1

* Fixed plugin manifest that incorrectly shipped a launcher activity and example app manifests, causing duplicate launcher icons and manifest merge conflicts in consumer apps
* Removed stray `android/app/` directory from the plugin package
* Cleaned plugin `AndroidManifest.xml` to only declare INTERNET permission — no more `tools:replace="android:label"` workaround needed

## 0.2.0

* Multi-connection chunked downloading (8 parallel connections per file) for maximum speed
* Video and audio streams now download simultaneously in parallel
* 512KB I/O buffers (up from 8KB) with BufferedOutputStream fallback
* OkHttp tuned with 32-connection pool, HTTP/1.1 forced for separate TCP per connection
* Progress callbacks throttled to 150ms to reduce Flutter overhead
* Automatic Range request detection with graceful single-connection fallback
* Updated README with download architecture documentation

## 0.1.3

* Replaced youtube_explode_dart with NewPipe Extractor for reliable YouTube extraction
* All YouTube extraction now runs natively on Android via Kotlin
* Real-time download progress tracking via EventChannel (no more fake progress)
* Removed dependency on youtube_explode_dart and path_provider
* Requires JitPack repository in consuming app's build.gradle

## 0.1.2

* Adjusted pub yaml
* flutter format fixed.

## 0.1.1

* Updated Readme



## 0.1.0

* Updated dependencies to latest versions
* Updated permission_handler to ^12.0.0+1
* Updated device_info_plus to ^11.4.0
* Improved compatibility with latest Flutter and Dart SDKs
* Fixed minor bugs and improved stability

## 0.0.9

* Added crucial Android manifest configuration documentation
* Added manifest configuration image reference
* Enhanced documentation with YouTube terms of service warnings
* Improved README with clearer usage instructions
* Added proper error messages for manifest configuration issues

## 0.0.8

* Added real-time download progress tracking
* Improved error handling and status messages
* Added support for video quality selection
* Implemented native Android MediaMuxer for video processing
* Added proper permission handling for Android storage
* Fixed video output path handling
* Added documentation and example implementation

## 0.0.7

* TODO: Describe initial release.

## 0.0.6

* TODO: Describe initial release.

## 0.0.5

* TODO: Describe initial release.

## 0.0.4

* TODO: Describe initial release.

## 0.0.3

* TODO: Describe initial release.

## 0.0.2

* TODO: Describe initial release.

## 0.0.1

* TODO: Describe initial release.
