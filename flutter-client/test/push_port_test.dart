import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

class PushRemote implements RemoteApi {
  @override
  String get baseUrl => 'https://im.example';
  final tokens = <String?>[];
  @override
  void bindScope(AccountScope scope) {}
  @override
  Future<JsonMap> request(String method, String path, {JsonMap? body}) async {
    if (path.endsWith('/push-token')) tokens.add(body!['token'] as String?);
    return {};
  }

  @override
  void close() {}
}

class TestPlatform implements IosPushPlatform, ForegroundPushPlatform {
  final controller = StreamController<JsonMap>.broadcast();
  final badges = <int>[];
  final foreground = <JsonMap>[];
  @override
  Future<void> configureForeground({
    required String appId,
    required String userId,
    required bool enabled,
    required bool sound,
  }) async {
    foreground.add({
      'app_id': appId,
      'user_id': userId,
      'enabled': enabled,
      'sound': sound,
    });
  }

  @override
  Stream<JsonMap> get events => controller.stream;
  @override
  Future<JsonMap?> initialNotification() async => null;
  @override
  Future<String?> currentToken() async => 'test-device-token';
  @override
  Future<bool> requestPermission() async => false;
  @override
  Future<void> setBadge(int count) async {
    badges.add(count);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('全部已读在途时串行写角标，旧数字完成后最终必须清零', () async {
    final remote = BadgeRemote();
    final client = await badgeClient(remote);
    final platform = DelayedBadgePlatform();
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (e, _) => errors.add(e),
    );
    await binding.attach();
    expect(platform.badges.last, 3);
    platform.delay = Completer<void>();
    remote.unread = 4;
    await client.sync();
    await platform.started.future;
    await client.markRead();
    // 原生旧写入尚未完成时，不能并发提交清零，否则旧回调会将角标写回4。
    expect(platform.badges, [3]);
    platform.delay!.complete();
    await platform.cleared.future;
    expect(platform.badges, [3, 4, 0]);
    remote.unread = 1;
    await client.sync();
    await platform.newArrival.future;
    expect(platform.badges.last, 1);
    expect(errors, isEmpty);
    await binding.dispose();
    await client.close(logout: false);
    await platform.controller.close();
  });
  test('token注册失败仍纠正角标；通知全读保留单聊未读', () async {
    final remote = BadgeRemote()..failToken = true;
    final client = await badgeClient(remote);
    final platform = TestPlatform();
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (e, _) => errors.add(e),
    );
    await binding.attach();
    expect(platform.badges, [3]);
    expect(errors.single, isA<StateError>());
    remote.chatUnread = 2;
    await client.markRead();
    await binding.dispose();
    // dispose可能取消尚未开始的更新，改用下一次绑定验证当前已提交总数。
    final next = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (_, _) {},
    );
    await next.attach();
    expect(platform.badges.last, 2);
    await next.dispose();
    await client.close(logout: false);
    await platform.controller.close();
  });
  test('释放等待旧账号在途角标，之后新账号清零不再被旧回调覆盖', () async {
    final remote = BadgeRemote();
    final client = await badgeClient(remote);
    final platform = DelayedBadgePlatform();
    final binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (_, _) {},
    );
    await binding.attach();
    platform.delay = Completer<void>();
    remote.unread = 4;
    await client.sync();
    await platform.started.future;
    var disposed = false;
    final disposing = binding.dispose().then((_) => disposed = true);
    await Future<void>.delayed(Duration.zero);
    expect(disposed, isFalse);
    platform.delay!.complete();
    await disposing;
    final nextClient = await badgeClient(
      BadgeRemote()
        ..unread = 0
        ..userId = 'bob',
    );
    final next = IosPushBinding(
      client: nextClient,
      platform: platform,
      onOpen: (_) {},
      onError: (_, _) {},
    );
    await next.attach();
    expect(platform.badges, [3, 4, 0]);
    await next.dispose();
    await client.close(logout: false);
    await nextClient.close(logout: false);
    await platform.controller.close();
  });
  test('系统角标写入失败上报宿主，下次同步可重试清零', () async {
    final client = await badgeClient(BadgeRemote());
    final platform = DelayedBadgePlatform()..failNext = true;
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (e, _) => errors.add(e),
    );
    await binding.attach();
    expect(errors.single, isA<StateError>());
    expect(platform.badges, isEmpty);
    await client.markRead();
    await platform.cleared.future;
    expect(platform.badges, [0]);
    await binding.dispose();
    await client.close(logout: false);
    await platform.controller.close();
  });
  test('注册失败保留未连接状态；重试成功后通知宿主；撤销权限会解绑旧 token', () async {
    final remote = FailingPushRemote();
    final platform = SettingsPlatform();
    final client = ImClient(
      api: remote,
      scope: const AccountScope(
        environment: 'https://im.example',
        appId: 'app',
        userId: 'alice',
      ),
      deviceId: 'ios',
      store: VolatileStore(),
    );
    final changes = <bool>[];
    late IosPushBinding binding;
    final errors = <Object>[];
    binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (error, _) => errors.add(error),
      onRegistrationChanged: () => changes.add(binding.registered),
    );
    expect(await binding.requestPermission(), isTrue);
    expect(errors.single, isA<StateError>());
    expect(binding.registered, isFalse);
    remote.fail = false;
    await binding.refreshRegistration();
    expect(binding.registered, isTrue);
    expect(platform.registrations, 2);
    platform.settings = const IosNotificationSettings(
      authorization: IosNotificationAuthorization.denied,
      lockScreen: false,
    );
    await binding.refreshRegistration();
    expect(binding.registered, isFalse);
    expect(remote.tokens, ['test-device-token', null]);
    expect(changes, [false, true, false]);
    expect(platform.registrations, 2);
    await binding.dispose();
    await client.close(logout: false);
    await platform.controller.close();
  });
  test('推送业务绑定可以替换 MethodChannel，拒绝授权仍正确解绑 token', () async {
    final remote = PushRemote();
    final platform = TestPlatform();
    final client = ImClient(
      api: remote,
      scope: const AccountScope(
        environment: 'https://im.example',
        appId: 'app',
        userId: 'alice',
      ),
      deviceId: 'ios',
      store: VolatileStore(),
    );
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      platform: platform,
      foregroundNotifications: true,
      onOpen: (_) {},
      onError: (error, _) {
        errors.add(error);
      },
    );
    await binding.attach();
    expect(remote.tokens, ['test-device-token']);
    expect(platform.badges, [0]);
    expect(platform.foreground.single, {
      'app_id': 'app',
      'user_id': 'alice',
      'enabled': true,
      'sound': true,
    });
    expect(await binding.requestPermission(), isFalse);
    expect(remote.tokens, ['test-device-token', null]);
    expect(errors, isEmpty);
    await binding.dispose();
    expect(platform.foreground.last['enabled'], isFalse);
    await client.close(logout: false);
    await platform.controller.close();
  });
}

/// 模拟系统授权变化与 APNs 重新注册，不执行真实系统调用。
class SettingsPlatform extends TestPlatform implements IosPushSettingsPlatform {
  IosNotificationSettings settings = const IosNotificationSettings(
    authorization: IosNotificationAuthorization.authorized,
    lockScreen: true,
  );
  int registrations = 0;
  @override
  Future<IosNotificationSettings> notificationSettings() async => settings;
  @override
  Future<bool> openNotificationSettings() async => true;
  @override
  Future<void> refreshRegistration() async {
    registrations++;
  }

  @override
  Future<bool> requestPermission() async => settings.allowed;
}

/// 模拟 token 上报失败，验证系统已授权时不会误报绑定成功。
class FailingPushRemote extends PushRemote {
  bool fail = true;
  @override
  Future<JsonMap> request(String method, String path, {JsonMap? body}) {
    if (fail && path.endsWith('/push-token')) {
      throw StateError('fixture upload failed');
    }
    return super.request(method, path, body: body);
  }
}

/// 提供已提交的通知／单聊未读总数，模拟全部已读及随后新到通知。
class BadgeRemote extends PushRemote {
  int unread = 3, chatUnread = 0;
  bool failToken = false;
  String userId = 'alice';
  @override
  Future<JsonMap> request(String method, String path, {JsonMap? body}) async {
    if (path == '/session') return {'app_id': 'app', 'user_id': userId};
    if (path.endsWith('/push-token')) {
      if (failToken) throw StateError('fixture token upload failure');
      return super.request(method, path, body: body);
    }
    if (path == '/read') unread = 0;
    return {
      'cursor': 'badge-cursor',
      'event_position': '1',
      'latest_seq': '4',
      'read_seq': unread == 0 ? '4' : '0',
      'unread_count': '$unread',
      'chat_unread_count': '$chatUnread',
      'messages': <JsonMap>[],
      'events': <JsonMap>[],
      'has_more': false,
    };
  }
}

/// 建立无真实网络的账号客户端，测试角标必须来自可靠同步后的计数。
Future<ImClient> badgeClient(BadgeRemote remote) async {
  final client = ImClient(
    api: remote,
    scope: AccountScope(
      environment: 'https://im.example',
      appId: 'app',
      userId: remote.userId,
    ),
    deviceId: 'ios',
    store: VolatileStore(),
  );
  await client.initialize(realtime: false);
  return client;
}

/// 控制系统角标写入完成顺序，模拟慢原生调用与新通知。
class DelayedBadgePlatform extends TestPlatform {
  Completer<void>? delay;
  bool failNext = false;
  final started = Completer<void>();
  final cleared = Completer<void>();
  final newArrival = Completer<void>();
  @override
  Future<void> setBadge(int count) async {
    if (failNext) {
      failNext = false;
      throw StateError('fixture native badge failure');
    }
    if (count == 4 && delay != null) {
      started.complete();
      await delay!.future;
    }
    await super.setBadge(count);
    if (count == 0 && !cleared.isCompleted) cleared.complete();
    if (count == 1 && !newArrival.isCompleted) newArrival.complete();
  }
}
