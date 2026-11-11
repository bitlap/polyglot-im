import 'dart:async';
import '../ports/ios_push_platform.dart';
import 'package:flutter/widgets.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

/// 绑定一个已认证账户。通知仅作为同步提示，点击后重新向服务端鉴权获取消息。
class PushBindingService extends WidgetsBindingObserver {
  PushBindingService({
    required this.client,
    required this.platform,
    required this.onOpen,
    required this.onError,
  });
  final SyncClient client;
  final void Function(ImMessage) onOpen;
  final void Function(Object, StackTrace) onError;
  final IosPushPlatform platform;
  StreamSubscription<JsonMap>? _subscription;
  StreamSubscription<JsonMap>? _changes;
  bool _disposed = false;
  final Set<String> _opened = {};

  Future<void> attach() async {
    if (_subscription != null) return;
    WidgetsBinding.instance.addObserver(this);
    _subscription = platform.events.listen(
      (event) => unawaited(_guard(() => _handle(event))),
      onError: (Object error, StackTrace stack) => onError(error, stack),
    );
    _changes = client.changes.listen((_) => unawaited(_badge()));
    await _guard(() async {
      final initial = await platform.initialNotification();
      if (initial != null) await _handle(initial);
      final token = await platform.currentToken();
      if (token != null && !_disposed) await _updateToken(token);
      await _badge();
    });
  }

  /// 应在宿主解释用途、用户主动操作后调用；拒绝授权不影响站内消息。
  Future<bool> requestPermission() async {
    final allowed = await platform.requestPermission();
    if (!allowed && !_disposed) await _updateToken(null);
    return allowed;
  }

  Future<void> _updateToken(String? token) async {
    if (!_disposed) await client.updatePushToken(token);
  }

  Future<void> _handle(JsonMap event) async {
    if (_disposed) return;
    if (event['type'] == 'token') {
      await _updateToken(event['token'] as String);
      return;
    }
    if (event['app_id'] != client.scope.appId ||
        event['user_id'] != client.scope.userId) {
      return;
    }
    await client.sync();
    if (event['type'] == 'opened') {
      final id = event['message_id'] as String?;
      if (id == null || _opened.contains(id)) return;
      final message = await client.getMessage(id);
      if (!_disposed && _opened.add(id)) {
        if (_opened.length > 256) _opened.remove(_opened.first);
        onOpen(message);
      }
    }
  }

  Future<void> _badge() => _guard(() async {
    if (!_disposed) {
      final count = client.unreadCount + client.chatUnreadCount;
      await platform.setBadge(count > BigInt.from(9999) ? 9999 : count.toInt());
    }
  });

  Future<void> _guard(Future<void> Function() run) async {
    try {
      await run();
    } catch (error, stack) {
      if (!_disposed) onError(error, stack);
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_disposed) {
      unawaited(
        _guard(() async {
          await client.sync();
          client.startRealtime();
          await _badge();
        }),
      );
    } else if (state == AppLifecycleState.paused) {
      unawaited(client.pauseRealtime());
    }
  }

  Future<void> dispose() async {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    await _subscription?.cancel();
    await _changes?.cancel();
  }
}
