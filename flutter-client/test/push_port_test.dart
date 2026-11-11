import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:polyglot_im_flutter_client/polyglot_im_flutter_client.dart';

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

class TestPlatform implements IosPushPlatform {
  final controller = StreamController<JsonMap>.broadcast();
  final badges = <int>[];
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
      onOpen: (_) {},
      onError: (error, _) {
        errors.add(error);
      },
    );
    await binding.attach();
    expect(remote.tokens, ['test-device-token']);
    expect(platform.badges, [0]);
    expect(await binding.requestPermission(), isFalse);
    expect(remote.tokens, ['test-device-token', null]);
    expect(errors, isEmpty);
    await binding.dispose();
    await client.close(logout: false);
    await platform.controller.close();
  });
}
