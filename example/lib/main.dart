import 'package:flutter/material.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const MyApp());
}

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

class YouTubeDownloaderScreen extends StatefulWidget {
  const YouTubeDownloaderScreen({super.key});

  @override
  State<YouTubeDownloaderScreen> createState() =>
      _YouTubeDownloaderScreenState();
}

class _YouTubeDownloaderScreenState extends State<YouTubeDownloaderScreen> {
  final _urlController = TextEditingController();
  final _downloader = YoutubeDownloader();
  List<VideoQuality> _qualities = [];
  bool _isLoading = false;
  String _status = '';
  double _progress = 0.0;
  String? _downloadedPath;

  @override
  void initState() {
    super.initState();
    _checkPermissions();
  }

  Future<void> _checkPermissions() async {
    if (await Permission.storage.request().isGranted) {
      // Permission granted
    }
  }

  Future<void> _getQualities() async {
    if (_urlController.text.isEmpty) {
      if (!mounted) return;
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
      if (!mounted) return;
      setState(() {
        _qualities = qualities;
        _status = 'Select quality to download';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'Error: $e');
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to get qualities: $e')));
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

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
          if (!mounted) return;
          setState(() => _downloadedPath = progress.outputPath);
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(
                'Download complete!\nSaved to: ${progress.outputPath}',
              ),
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
      appBar: AppBar(title: const Text('YouTube Downloader')),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _urlController,
              decoration: const InputDecoration(
                labelText: 'YouTube URL',
                hintText: 'Enter YouTube video URL',
              ),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _isLoading ? null : _getQualities,
              child: Text(_isLoading ? 'Loading...' : 'Get Qualities'),
            ),
            const SizedBox(height: 16),
            if (_qualities.isNotEmpty) ...[
              const Text('Available Qualities:'),
              Expanded(
                child: ListView.builder(
                  itemCount: _qualities.length,
                  itemBuilder: (context, index) {
                    final quality = _qualities[index];
                    return ListTile(
                      title: Text(quality.quality),
                      subtitle: Text(
                        'Size: ${(quality.size / 1024 / 1024).toStringAsFixed(2)} MB',
                      ),
                      onTap: () => _downloadVideo(quality),
                    );
                  },
                ),
              ),
            ],
            if (_progress > 0) ...[
              const SizedBox(height: 16),
              LinearProgressIndicator(value: _progress),
              Text('Progress: ${(_progress * 100).toStringAsFixed(1)}%'),
              Text('Status: $_status'),
            ],
            if (_downloadedPath != null) ...[
              const SizedBox(height: 16),
              Text(
                'Last downloaded file:',
                style: Theme.of(context).textTheme.titleSmall,
              ),
              Text(
                _downloadedPath!,
                style: const TextStyle(fontFamily: 'monospace'),
              ),
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
