import 'package:flutter/services.dart';
import 'package:polyglot_im/polyglot_im.dart';
import '../../ports/ios_push_platform.dart';

/// 通过 Flutter 原生通道读取 iOS 推送事件、权限、token 和角标。
class MethodChannelPush
    implements
        IosPushPlatform,
        ForegroundPushPlatform,
        IosPushSettingsPlatform {
  /// 创建无状态的 iOS 方法/事件通道适配器，实际系统调用按需执行。
  const MethodChannelPush();
  static const _methods = MethodChannel('polyglot_im/apns');
  static const _events = EventChannel('polyglot_im/apns_events');

  /// 订阅原生推送 token 或通知点击事件。
  @override
  Stream<JsonMap> get events => _events.receiveBroadcastStream().map(
    (event) => Map<String, dynamic>.from(event as Map),
  );

  /// 读取触发本次冷启动的通知，可能没有对应通知。
  @override
  Future<JsonMap?> initialNotification() =>
      _methods.invokeMapMethod<String, dynamic>('initialNotification');

  /// 读取当前 APNs token；尚未注册时返回空值。
  @override
  Future<String?> currentToken() =>
      _methods.invokeMethod<String>('currentToken');

  /// 请求系统通知权限并返回授权结果；拒绝不影响站内同步。
  @override
  Future<bool> requestPermission() async =>
      await _methods.invokeMethod<bool>('requestPermission') ?? false;

  /// 读取系统通知授权和锁屏开关；未知返回值作为桥接错误处理。
  @override
  Future<IosNotificationSettings> notificationSettings() async {
    final result = await _methods.invokeMapMethod<String, dynamic>(
      'notificationSettings',
    );
    final authorization = IosNotificationAuthorization.values.byName(
      result?['authorization'] as String? ?? '',
    );
    return IosNotificationSettings(
      authorization: authorization,
      lockScreen: result?['lock_screen'] == true,
    );
  }

  /// 由用户操作触发，打开本应用的系统通知设置。
  @override
  Future<bool> openNotificationSettings() async =>
      await _methods.invokeMethod<bool>('openNotificationSettings') ?? false;

  /// 已获系统授权时重新注册 APNs，结果通过原有 token 或错误事件返回。
  @override
  Future<void> refreshRegistration() =>
      _methods.invokeMethod<void>('refreshRegistration');

  /// 设置系统角标数量，不把角标视为服务端未读状态。
  @override
  Future<void> setBadge(int count) =>
      _methods.invokeMethod<void>('setBadge', count);

  /// 向原生层绑定当前账号，确保前台系统通知不会泄漏其他账号的提醒。
  @override
  Future<void> configureForeground({
    required String appId,
    required String userId,
    required bool enabled,
    required bool sound,
  }) => _methods.invokeMethod<void>('configureForeground', {
    'app_id': appId,
    'user_id': userId,
    'enabled': enabled,
    'sound': sound,
  });
}
