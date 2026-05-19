
## 0.4.7

* **Fix: frequent "Could not load stream info" errors** — The retry logic in `getStreamInfoWithRetry` now retries on NewPipe `ParsingException` and `ExtractionException` (e.g. YouTube page format changes, incomplete responses) in addition to `IOException`. These errors are the primary cause of the frequent failures because NewPipe's own exceptions are not `IOException` subclasses. Permanent errors (`ContentNotAvailableException`, `ReCaptchaException`) are still rethrown immediately. Retry attempts increased from 1 to 3, with exponential back-off (1 s, then 3 s).

## 0.4.6

* **Fix: intermittent "No compatible audio stream found" and "Could not load stream info"** — Added a 5-minute `StreamInfo` cache keyed by URL so `getQualities()` and `downloadAudio()`/`downloadStreams()` always operate on the same set of streams (YouTube sometimes returns different delivery methods on back-to-back calls to the same URL). Also broadened the retry in `getStreamInfoWithRetry` from DNS-only (`UnknownHostException`) to any transient I/O failure (`IOException`), covering connection resets, timeouts, and other transient network errors.

## 0.4.5

* **Fix: "download completed but no output path returned" race condition** — Audio download progress can legitimately reach 1.0 (last batch of bytes) *before* the "Download completed" event carrying `outputPath` is posted. The Dart generator now breaks only when `outputPath != null` (not on `progress >= 1.0`), and the Kotlin side caps download-phase progress at 0.99 so 1.0 is reserved exclusively for the completion event.

## 0.4.4

* **Fix: "unable to resolve www.youtube.com" on first download** — The plugin now pre-warms the network immediately on attach (app startup): it initializes NewPipe and resolves `www.youtube.com` in the background so the Android DNS cache is hot by the time the user triggers a download. A single automatic retry (2 s delay) is kept as a safety net for any edge cases where the pre-warm loses a race.

## 0.4.3

* **Fix: `downloadAudio` no longer fails with "No compatible audio stream found"** — now prefers `audio/mp4` (AAC/M4A) but falls back to any progressive HTTP audio stream (e.g. `audio/webm`). Output extension is derived from the actual stream format.
* **Fix: add `consumer-rules.pro`** — ProGuard/R8 keep rules for OkHttp, NewPipe Extractor, and plugin classes are now shipped with the plugin so release builds work without manual configuration.

## 0.4.2

* **Fix: quality label mismatch no longer fails download** — `downloadVideo()` now uses lenient resolution matching: tries exact label first, then same resolution number (handles `"720p"` vs `"720p60"` drift between two `StreamInfo.getInfo()` calls), then nearest available quality as last resort
* **Integration tests** — all three methods (`getQualities`, `downloadAudio`, `downloadVideo`) verified end-to-end on a real device

## 0.4.1

* **Fix: download errors no longer hang** — if the native call fails, the error is now propagated into the progress stream immediately so `downloadVideo()` / `downloadAudio()` reject properly instead of waiting forever
* **Perf: one fewer network round-trip** — title is now resolved inside `downloadStreams` / `downloadAudio` via an `onTitleKnown` callback, removing the separate `getVideoTitle()` call that fetched stream info twice

## 0.4.0

* **New `downloadAudio(url)`** — downloads the best-quality MP4/M4A audio stream with full progress tracking. No quality selection needed; the native side auto-picks the highest-bitrate audio stream. Returns progress 0.0–1.0 and a final `.m4a` output path. Mirrors `downloadVideo()` but skips the mux step entirely.
* **`getQualities()` now returns audio streams** — audio-only entries have `fps = 0`, video entries remain unchanged. Callers can filter by `fps` to distinguish them and show audio info (bitrate, size) before downloading.

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
