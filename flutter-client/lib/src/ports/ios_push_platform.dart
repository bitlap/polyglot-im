import 'package:polyglot_im/polyglot_im.dart';

/// iOS 当前通知授权，区分尚未申请与已拒绝，不能用 token 是否存在代替。
enum IosNotificationAuthorization {
  notDetermined,
  denied,
  authorized,
  provisional,
  ephemeral,
}

/// 系统通知设置快照；授权不代表 APNs 注册或服务端绑定成功。
class IosNotificationSettings {
  /// 保存系统实际返回的授权与锁屏展示能力。
  const IosNotificationSettings({
    required this.authorization,
    required this.lockScreen,
  });
  final IosNotificationAuthorization authorization;
  final bool lockScreen;

  /// 包含临时授权；静默试用授权仍须单独检查锁屏开关。
  bool get allowed => switch (authorization) {
    IosNotificationAuthorization.authorized ||
    IosNotificationAuthorization.provisional ||
    IosNotificationAuthorization.ephemeral => true,
    _ => false,
  };
}

/// 可选的 iOS 设置端口，不要求其他推送适配器实现系统设置跳转。
abstract interface class IosPushSettingsPlatform {
  /// 查询真实系统授权，不触发授权弹窗。
  Future<IosNotificationSettings> notificationSettings();

  /// 打开本应用的通知设置；系统无法打开时返回 false。
  Future<bool> openNotificationSettings();

  /// 重新向 APNs 注册；用于授权变化或暂时失败后的恢复，不再次请求授权。
  Future<void> refreshRegistration();
}

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
