import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('YoutubeDownloader getQualities test', (WidgetTester tester) async {
    final YoutubeDownloader downloader = YoutubeDownloader();

    // Example video
    const testUrl = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'; // Replace with a real test video URL
    
    final qualities = await downloader.getQualities(testUrl);

    expect(qualities.isNotEmpty, true); // We expect at least one video quality
  });
}
