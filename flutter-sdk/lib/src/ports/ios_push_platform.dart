import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

abstract interface class IosPushPlatform {
  Stream<JsonMap> get events;
  Future<JsonMap?> initialNotification();
  Future<String?> currentToken();
  Future<bool> requestPermission();
  Future<void> setBadge(int count);
}
