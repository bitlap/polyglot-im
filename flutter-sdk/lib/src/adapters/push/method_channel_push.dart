import 'package:flutter/services.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
import '../../ports/ios_push_platform.dart';

class MethodChannelPush implements IosPushPlatform {
  const MethodChannelPush();
  static const _methods = MethodChannel('polyglot_im/apns');
  static const _events = EventChannel('polyglot_im/apns_events');
  @override
  Stream<JsonMap> get events => _events.receiveBroadcastStream().map(
    (event) => Map<String, dynamic>.from(event as Map),
  );
  @override
  Future<JsonMap?> initialNotification() =>
      _methods.invokeMapMethod<String, dynamic>('initialNotification');
  @override
  Future<String?> currentToken() =>
      _methods.invokeMethod<String>('currentToken');
  @override
  Future<bool> requestPermission() async =>
      await _methods.invokeMethod<bool>('requestPermission') ?? false;
  @override
  Future<void> setBadge(int count) =>
      _methods.invokeMethod<void>('setBadge', count);
}
