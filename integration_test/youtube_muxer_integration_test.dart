import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';
import 'package:youtube_muxer_2025/src/models/download_progress.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  const testUrl = 'https://youtu.be/W4oyMuLmozY?si=lNX4Sp6RN2LBH_VH';
  late YoutubeDownloader downloader;

  setUp(() {
    downloader = YoutubeDownloader();
  });

  // ── getQualities ───────────────────────────────────────────────────────────

  testWidgets('getQualities returns video and audio streams', (tester) async {
    final qualities = await downloader.getQualities(testUrl);

    final video = qualities.where((q) => q.fps > 0).toList();
    final audio = qualities.where((q) => q.fps == 0).toList();

    debugPrint('=== getQualities ===');
    debugPrint('Total streams: ${qualities.length}');
    for (final q in qualities) {
      final type = q.fps > 0 ? 'VIDEO' : 'AUDIO';
      debugPrint('[$type] ${q.quality} | ${(q.size / 1024 / 1024).toStringAsFixed(1)} MB'
          ' | ${q.bitrate ~/ 1000} kbps | fps=${q.fps}');
    }

    expect(qualities, isNotEmpty, reason: 'Should return at least one stream');
    expect(video, isNotEmpty, reason: 'Should return at least one video quality');
    expect(audio, isNotEmpty, reason: 'Should return at least one audio stream');

    for (final q in video) {
      expect(q.fps, greaterThan(0));
      expect(q.size, greaterThan(0));
      expect(q.quality, isNotEmpty);
    }
    for (final q in audio) {
      expect(q.fps, equals(0));
      expect(q.bitrate, greaterThan(0));
    }
  }, timeout: const Timeout(Duration(minutes: 2)));

  // ── downloadAudio ──────────────────────────────────────────────────────────

  testWidgets('downloadAudio downloads and returns a valid .m4a path',
      (tester) async {
    debugPrint('=== downloadAudio ===');

    final events = <DownloadProgress>[];
    await for (final p in downloader.downloadAudio(testUrl)) {
      events.add(p);
      debugPrint('progress=${p.progress.toStringAsFixed(2)}'
          ' status="${p.status}"'
          ' path=${p.outputPath ?? "-"}');
    }

    expect(events, isNotEmpty, reason: 'Should emit at least one progress event');

    final last = events.last;
    expect(last.progress, closeTo(1.0, 0.01),
        reason: 'Final progress should be 1.0');
    expect(last.outputPath, isNotNull,
        reason: 'Final event should carry the output path');
    expect(last.outputPath, endsWith('.m4a'),
        reason: 'Output should be an .m4a file');

    final file = File(last.outputPath!);
    expect(file.existsSync(), isTrue,
        reason: 'Downloaded file must exist on disk');
    expect(file.lengthSync(), greaterThan(0),
        reason: 'Downloaded file must not be empty');

    debugPrint('Audio saved to: ${last.outputPath} (${file.lengthSync()} bytes)');
  }, timeout: const Timeout(Duration(minutes: 5)));

  // ── downloadVideo ──────────────────────────────────────────────────────────

  testWidgets('downloadVideo downloads and muxes a valid .mp4 file',
      (tester) async {
    debugPrint('=== downloadVideo ===');

    // Pick the lowest-resolution video quality to keep test fast
    final qualities = await downloader.getQualities(testUrl);
    final videoQualities = qualities.where((q) => q.fps > 0).toList()
      ..sort((a, b) => a.size.compareTo(b.size)); // smallest first
    expect(videoQualities, isNotEmpty, reason: 'Need at least one video quality');

    final selected = videoQualities.first;
    debugPrint('Selected quality: ${selected.quality}'
        ' (${(selected.size / 1024 / 1024).toStringAsFixed(1)} MB)');

    final events = <DownloadProgress>[];
    await for (final p in downloader.downloadVideo(selected, testUrl)) {
      events.add(p);
      debugPrint('progress=${p.progress.toStringAsFixed(2)}'
          ' status="${p.status}"'
          ' path=${p.outputPath ?? "-"}');
    }

    expect(events, isNotEmpty, reason: 'Should emit at least one progress event');

    final last = events.last;
    expect(last.progress, closeTo(1.0, 0.01),
        reason: 'Final progress should be 1.0');
    expect(last.outputPath, isNotNull,
        reason: 'Final event should carry the output path');
    expect(last.outputPath, endsWith('.mp4'),
        reason: 'Output should be an .mp4 file');

    final file = File(last.outputPath!);
    expect(file.existsSync(), isTrue,
        reason: 'Downloaded file must exist on disk');
    expect(file.lengthSync(), greaterThan(0),
        reason: 'Downloaded file must not be empty');

    debugPrint('Video saved to: ${last.outputPath} (${file.lengthSync()} bytes)');
  }, timeout: const Timeout(Duration(minutes: 10)));
}
