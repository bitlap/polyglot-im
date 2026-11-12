import 'dart:async';
import '../ports/ios_push_platform.dart';
import 'package:flutter/widgets.dart';
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';

/// 绑定一个已认证账户。通知仅作为同步提示，点击后重新向服务端鉴权获取消息。
class PushBindingService extends WidgetsBindingObserver {
  /// 保存客户端、原生推送端口和回调；attach 后才订阅事件与生命周期。
  PushBindingService({
    required this.client,
    required this.platform,
    required this.onOpen,
    required this.onError,
    this.foregroundNotifications = false,
    this.foregroundSound = true,
  });
  final bool foregroundNotifications;
  final bool foregroundSound;
  final SyncClient client;
  final void Function(ImMessage) onOpen;
  final void Function(Object, StackTrace) onError;
  final IosPushPlatform platform;
  StreamSubscription<JsonMap>? _subscription;
  StreamSubscription<JsonMap>? _changes;
  bool _disposed = false;
  final Set<String> _opened = {};

  /// 注册生命周期与事件订阅；重复调用不重复挂载。
  Future<void> attach() async {
    if (_subscription != null) return;
    WidgetsBinding.instance.addObserver(this);
    _subscription = platform.events.listen(
      (event) => unawaited(_guard(() => _handle(event))),
      onError: (Object error, StackTrace stack) => onError(error, stack),
    );
    _changes = client.changes.listen((_) => unawaited(_badge()));
    await _guard(() async {
      if (platform case final ForegroundPushPlatform configurable) {
        await configurable.configureForeground(
          appId: client.scope.appId,
          userId: client.scope.userId,
          enabled: foregroundNotifications,
          sound: foregroundSound,
        );
      }
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

  /// 根据前后台变化暂停或恢复处理，后台不提交用户阅读确认。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_disposed) {
      unawaited(
        _guard(() async {
          await client.sync();
          client.startRealtime();
          client.startPolling();
          await _badge();
        }),
      );
    } else if (state == AppLifecycleState.paused) {
      client.stopPolling();
      unawaited(client.pauseRealtime());
    }
  }

  /// 解除订阅并释放本组件持有的资源，迟到回调不再更新界面。
  Future<void> dispose() async {
    _disposed = true;
    WidgetsBinding.instance.removeObserver(this);
    await _subscription?.cancel();
    await _changes?.cancel();
    if (platform case final ForegroundPushPlatform configurable) {
      await _guard(
        () => configurable.configureForeground(
          appId: client.scope.appId,
          userId: client.scope.userId,
          enabled: false,
          sound: false,
        ),
      );
    }
  }
}
