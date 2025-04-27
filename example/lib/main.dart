import 'dart:io';
import 'package:flutter/material.dart';
import 'package:youtube_muxer_2025/youtube_muxer_2025.dart';
import 'package:video_player/video_player.dart';
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
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const YouTubeDownloaderScreen(),
    );
  }
}

class YouTubeDownloaderScreen extends StatefulWidget {
  const YouTubeDownloaderScreen({super.key});

  @override
  State<YouTubeDownloaderScreen> createState() => _YouTubeDownloaderScreenState();
}

class _YouTubeDownloaderScreenState extends State<YouTubeDownloaderScreen> {
  final _urlController = TextEditingController();
  final _downloader = YoutubeDownloader(); // ✅ Create instance of YoutubeDownloader
  List<VideoQuality> _qualities = [];
  bool _isLoading = false;
  String _status = '';
  double _progress = 0.0;
  VideoPlayerController? _videoController;
  bool _isPlaying = false;

  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  Future<void> _requestPermissions() async {
    // Storage permission (for Android < 13)
    if (await Permission.storage.isDenied) {
      final status = await Permission.storage.request();
      debugPrint('Storage permission status: $status');
    }
    
    // For Android 13 and above
    if (await Permission.photos.isDenied) {
      final status = await Permission.photos.request();
      debugPrint('Photos permission status: $status');
    }
    if (await Permission.videos.isDenied) {
      final status = await Permission.videos.request();
      debugPrint('Videos permission status: $status');
    }
    
    // For iOS
    if (await Permission.mediaLibrary.isDenied) {
      final status = await Permission.mediaLibrary.request();
      debugPrint('Media Library permission status: $status');
    }

    // Log final permission states
    final permissions = await Future.wait([
      Permission.storage.status,
      Permission.photos.status,
      Permission.videos.status,
      Permission.mediaLibrary.status,
    ]);

    debugPrint('''
=== Permission Status ===
Storage: ${permissions[0]}
Photos: ${permissions[1]}
Videos: ${permissions[2]}
Media Library: ${permissions[3]}
=====================
''');
  }

  @override
  void dispose() {
    _urlController.dispose();
    _videoController?.dispose();
    super.dispose();
  }

  Future<void> _initializeVideo(String path) async {
    _videoController?.dispose();
    _videoController = VideoPlayerController.file(File(path));
    
    await _videoController!.initialize();
    setState(() {
      _isPlaying = true;
      _videoController!.play();
    });
  }

  Future<void> _getQualities() async {
    debugPrint('Starting _getQualities()'); // Add this debug print
    if (_urlController.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Please enter a YouTube URL')),
      );
      return;
    }

    setState(() {
      _isLoading = true;
      _status = 'Fetching qualities...';
      _qualities = [];  // Clear previous qualities
    });

    try {
      debugPrint('Attempting to fetch qualities for: ${_urlController.text}');
      final qualities = await _downloader.getQualities(_urlController.text);
      
      if (!mounted) return;
      
      setState(() {
        _qualities = qualities;
        _status = 'Found ${qualities.length} qualities';
      });
      
      debugPrint('Successfully fetched ${qualities.length} qualities');
    } catch (e) {
      debugPrint('Error in _getQualities: $e');
      
      if (!mounted) return;
      
      setState(() => _status = 'Error: $e');
      
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Failed to fetch video: $e'),
          backgroundColor: Colors.red,
          duration: const Duration(seconds: 5),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _downloadVideo(VideoQuality quality) async {
    setState(() {
      _isLoading = true;
      _progress = 0.0;
      _status = 'Starting download...';
    });

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

        // If download completed and we have the output path
        if (progress.progress == 1.0 && progress.outputPath != null) {
          await _initializeVideo(progress.outputPath!);
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
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('YouTube Downloader')),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _urlController,
                decoration: const InputDecoration(
                  labelText: 'YouTube URL',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: _isLoading ? null : _getQualities,
                child: const Text('Get Qualities'),
              ),
              if (_isLoading) ...[
                const SizedBox(height: 12),
                LinearProgressIndicator(value: _progress),
                const SizedBox(height: 8),
                Text(_status, textAlign: TextAlign.center),
              ],
              if (_videoController != null && _videoController!.value.isInitialized) ...[
                const SizedBox(height: 20),
                AspectRatio(
                  aspectRatio: _videoController!.value.aspectRatio,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      VideoPlayer(_videoController!),
                      IconButton(
                        icon: Icon(
                          _isPlaying ? Icons.pause : Icons.play_arrow,
                          size: 50.0,
                          color: Colors.white,
                        ),
                        onPressed: () {
                          setState(() {
                            _isPlaying = !_isPlaying;
                            _isPlaying 
                              ? _videoController!.play()
                              : _videoController!.pause();
                          });
                        },
                      ),
                    ],
                  ),
                ),
                VideoProgressIndicator(
                  _videoController!,
                  allowScrubbing: true,
                  padding: const EdgeInsets.symmetric(vertical: 8),
                ),
              ],
              const SizedBox(height: 20),
              if (_qualities.isNotEmpty)
                ListView.builder(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  itemCount: _qualities.length,
                  itemBuilder: (context, index) {
                    final quality = _qualities[index];
                    return Card(
                      child: ListTile(
                        title: Text('${quality.quality} (${(quality.size / 1024 / 1024).toStringAsFixed(2)} MB)'),
                        subtitle: Text('Codec: ${quality.codec}'),
                        trailing: ElevatedButton(
                          onPressed: _isLoading ? null : () => _downloadVideo(quality),
                          child: const Text('Download'),
                        ),
                      ),
                    );
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }
}
