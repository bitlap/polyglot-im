import 'dart:convert';

/// 环境、应用和用户组成的缓存隔离边界，不用设备身份代替账号。
class AccountScope {
  /// 记录环境、应用和用户身份，作为本地缓存与请求身份校验的边界。
  const AccountScope({
    required this.environment,
    required this.appId,
    required this.userId,
  });
  final String environment, appId, userId;

  /// 派生当前作用域的稳定隔离键，不把不同应用或用户的缓存混在一起。
  String get key =>
      jsonEncode([environment.replaceFirst(RegExp(r'/$'), ''), appId, userId]);
}
