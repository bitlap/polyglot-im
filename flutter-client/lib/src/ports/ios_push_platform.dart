import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';

/// Flutter 与 iOS 推送能力的端口，隔离权限、token、点击和角标。
abstract interface class IosPushPlatform {
  /// 订阅原生推送 token 或通知点击事件。
  Stream<JsonMap> get events;

  /// 读取触发本次冷启动的通知，可能没有对应通知。
  Future<JsonMap?> initialNotification();

  /// 读取当前 APNs token；尚未注册时返回空值。
  Future<String?> currentToken();

  /// 请求系统通知权限并返回授权结果；拒绝不影响站内同步。
  Future<bool> requestPermission();

  /// 设置系统角标数量，不把角标视为服务端未读状态。
  Future<void> setBadge(int count);
}

/// 可选的前台系统通知策略端口；其他推送平台无需实现。
abstract interface class ForegroundPushPlatform {
  /// 配置当前账户的系统横幅；静音时不播放声音，未知账户通知不展示。
  Future<void> configureForeground({
    required String appId,
    required String userId,
    required bool enabled,
    required bool sound,
  });
}
