import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const methods = MethodChannel('polyglot_im/apns');
  const events = MethodChannel('polyglot_im/apns_events');
  const scope = AccountScope(
    environment: 'https://im.example',
    appId: 'app',
    userId: 'alice',
  );
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() {
    messenger.setMockMethodCallHandler(methods, null);
    messenger.setMockMethodCallHandler(events, null);
  });

  test('拒绝通知权限解绑 token，不覆盖设备偏好且站内同步仍可用', () async {
    JsonMap? deviceUpdate;
    final network = MockClient((request) async {
      if (request.method == 'PUT') {
        deviceUpdate = jsonDecode(request.body) as JsonMap;
      }
      return http.Response(
        jsonEncode({
          'ok': true,
          'cursor': 'fresh',
          'event_position': '1',
          'latest_seq': '0',
          'read_seq': '0',
          'unread_count': '0',
          'messages': [],
        }),
        200,
      );
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: scope.environment,
        tokenProvider: () async => 'user',
        client: network,
      ),
      scope: scope,
      deviceId: 'ios',
      store: VolatileStore(),
    );
    messenger.setMockMethodCallHandler(
      methods,
      (call) async => call.method == 'requestPermission' ? false : null,
    );
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      onOpen: (_) => fail('must not navigate'),
      onError: (e, _) => errors.add(e),
    );
    expect(await binding.requestPermission(), isFalse);
    expect(deviceUpdate, {'token': null});
    await client.sync();
    expect(client.cache['cursor'], 'fresh');
    expect(errors, isEmpty);
    await binding.dispose();
    await client.close(logout: false);
    network.close();
  });
  test('冷启动通知不匹配当前账户时不查询消息、不导航；重复 attach 只监听一次', () async {
    var nativeListeners = 0;
    final requests = <String>[];
    messenger.setMockMethodCallHandler(events, (call) async {
      if (call.method == 'listen') nativeListeners++;
      return null;
    });
    messenger.setMockMethodCallHandler(methods, (call) async {
      if (call.method == 'initialNotification') {
        return {
          'type': 'opened',
          'app_id': 'app',
          'user_id': 'bob',
          'message_id': 'private',
        };
      }
      return null;
    });
    final network = MockClient((request) async {
      requests.add(request.url.path);
      return http.Response('{}', 200);
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: scope.environment,
        tokenProvider: () async => 'user',
        client: network,
      ),
      scope: scope,
      deviceId: 'ios',
      store: VolatileStore(),
    );
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      onOpen: (_) => fail('must not open another user notification'),
      onError: (e, _) => errors.add(e),
    );
    await binding.attach();
    await binding.attach();
    expect(nativeListeners, 1);
    expect(requests, isEmpty);
    expect(errors, isEmpty);
    await binding.dispose();
    await client.close(logout: false);
    network.close();
  });
  test('冷启动点击先验证消息再回调，查询失败不会绕过鉴权导航', () async {
    messenger.setMockMethodCallHandler(events, (_) async => null);
    messenger.setMockMethodCallHandler(
      methods,
      (call) async => call.method == 'initialNotification'
          ? {
              'type': 'opened',
              'app_id': 'app',
              'user_id': 'alice',
              'message_id': 'missing',
            }
          : null,
    );
    final paths = <String>[];
    final network = MockClient((request) async {
      paths.add(request.url.path);
      if (request.url.path.contains('/messages/')) {
        return http.Response('{"code":"NOT_FOUND"}', 404);
      }
      return http.Response(
        '{"cursor":"fresh","event_position":"1","latest_seq":"0","read_seq":"0","unread_count":"0","messages":[]}',
        200,
      );
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: scope.environment,
        tokenProvider: () async => 'user',
        client: network,
      ),
      scope: scope,
      deviceId: 'ios',
      store: VolatileStore(),
    );
    final errors = <Object>[];
    final binding = IosPushBinding(
      client: client,
      onOpen: (_) => fail('must not navigate without a readable message'),
      onError: (e, _) => errors.add(e),
    );
    await binding.attach();
    expect(paths, ['/api/v1/im/snapshot', '/api/v1/im/messages/missing']);
    expect(errors.single, isA<ImException>());
    await binding.dispose();
    await client.close(logout: false);
    network.close();
  });
}
