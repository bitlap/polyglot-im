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
    binding = IosPushBinding(
      client: client,
      platform: platform,
      onOpen: (_) {},
      onError: (_, _) {},
      onRegistrationChanged: () => changes.add(binding.registered),
    );
    await expectLater(binding.requestPermission(), throwsStateError);
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
