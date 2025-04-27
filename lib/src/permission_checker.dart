import 'dart:io';
import 'package:permission_handler/permission_handler.dart';
import 'package:device_info_plus/device_info_plus.dart';

class PermissionChecker {
  static Future<bool> hasStoragePermission() async {
    if (Platform.isAndroid) {
      final deviceInfo = DeviceInfoPlugin();
      final androidInfo = await deviceInfo.androidInfo;
      
      if (androidInfo.version.sdkInt >= 33) {
        // For Android 13 and above, check photos and videos permissions
        return await Permission.photos.status.isGranted && 
               await Permission.videos.status.isGranted;
      } else {
        // For Android 12 and below, check storage permission
        return await Permission.storage.status.isGranted;
      }
    } else if (Platform.isIOS) {
      // For iOS, check photos and media library permissions
      return await Permission.photos.status.isGranted && 
             await Permission.mediaLibrary.status.isGranted;
    }
    return false;
  }
}
