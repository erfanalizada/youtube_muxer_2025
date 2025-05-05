import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('End-to-End Tests', () {
    late YoutubeDownloader downloader;

    setUp(() {
      downloader = YoutubeDownloader();
    });

    testWidgets('Full download and mux process', (tester) async {
      const testUrl = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ';

      // Test getting qualities
      final qualities = await downloader.getQualities(testUrl);
      expect(qualities.isNotEmpty, true);

      // Test downloading first available quality
      final firstQuality = qualities.first;
      int progressUpdates = 0;
      String? finalPath;

      await for (final progress
          in downloader.downloadVideo(firstQuality, testUrl)) {
        progressUpdates++;
        if (progress.progress == 1.0) {
          finalPath = progress.outputPath;
        }
      }

      expect(progressUpdates, greaterThan(0));
      expect(finalPath, isNotNull);
    });
  });
}
