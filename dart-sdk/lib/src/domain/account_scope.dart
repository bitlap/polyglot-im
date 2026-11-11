import 'dart:convert';

class AccountScope {
  const AccountScope({
    required this.environment,
    required this.appId,
    required this.userId,
  });
  final String environment, appId, userId;
  String get key =>
      jsonEncode([environment.replaceFirst(RegExp(r'/$'), ''), appId, userId]);
}
