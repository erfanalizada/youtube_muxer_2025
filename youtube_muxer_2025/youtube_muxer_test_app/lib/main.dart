import 'package:flutter/material.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: DownloaderTestScreen(),
    );
  }
}

class DownloaderTestScreen extends StatefulWidget {
  @override
  State<DownloaderTestScreen> createState() => _DownloaderTestScreenState();
}

class _DownloaderTestScreenState extends State<DownloaderTestScreen> {
  final _urlController = TextEditingController();
  final _downloader = YoutubeDownloader();
  String _status = '';
  double _progress = 0;
  List<VideoQuality> _qualities = [];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('YouTube Muxer Test')),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            TextField(
              controller: _urlController,
              decoration: const InputDecoration(
                labelText: 'YouTube URL',
                hintText: 'https://www.youtube.com/watch?v=...',
              ),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _getQualities,
              child: const Text('Get Qualities'),
            ),
            const SizedBox(height: 16),
            if (_qualities.isNotEmpty) ...[
              Text('Available Qualities:'),
              ListView.builder(
                shrinkWrap: true,
                itemCount: _qualities.length,
                itemBuilder: (context, index) {
                  return ListTile(
                    title: Text(_qualities[index].quality),
                    onTap: () => _downloadVideo(_qualities[index]),
                  );
                },
              ),
            ],
            if (_progress > 0) ...[
              const SizedBox(height: 16),
              LinearProgressIndicator(value: _progress),
              Text('Progress: ${(_progress * 100).toStringAsFixed(1)}%'),
              Text('Status: $_status'),
            ],
          ],
        ),
      ),
    );
  }

  Future<void> _getQualities() async {
    try {
      final qualities = await _downloader.getQualities(_urlController.text);
      setState(() {
        _qualities = qualities;
        _status = 'Found ${qualities.length} qualities';
      });
    } catch (e) {
      setState(() => _status = 'Error: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: $e')),
      );
    }
  }

  Future<void> _downloadVideo(VideoQuality quality) async {
    try {
      await for (final progress in _downloader.downloadVideo(
        quality,
        _urlController.text,
      )) {
        setState(() {
          _progress = progress.progress;
          _status = progress.status;
        });

        if (progress.progress == 1.0 && progress.outputPath != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Download complete: ${progress.outputPath}'),
              duration: const Duration(seconds: 5),
            ),
          );
        }
      }
    } catch (e) {
      setState(() => _status = 'Error: $e');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Download failed: $e')),
      );
    }
  }
}