import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
import 'package:test/test.dart';

const scope = AccountScope(
  environment: 'https://im.example',
  appId: 'app',
  userId: 'alice',
);
http.Response response(JsonMap value, [int status = 200]) => http.Response(
  jsonEncode(value),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
JsonMap snapshot() => {
  'cursor': 'fresh',
  'event_position': '3',
  'latest_seq': '0',
  'read_seq': '0',
  'unread_count': '0',
  'messages': [],
};

void main() {
  test('并发 sync 合并为一次请求，落盘后才更新缓存', () async {
    final gate = Completer<void>();
    var calls = 0;
    final network = MockClient((request) async {
      calls++;
      await gate.future;
      return response(snapshot());
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
    final pending = List.generate(8, (_) => client.sync());
    await Future<void>.delayed(Duration.zero);
    expect(calls, 1);
    expect(client.cache['cursor'], isNull);
    gate.complete();
    await Future.wait(pending);
    expect(client.cache['cursor'], 'fresh');
    await client.close(logout: false);
    network.close();
  });
  test('RESET_REQUIRED 重新取快照；身份不匹配不接收他人快照', () async {
    final store = VolatileStore();
    await store.commit(scope, {'cursor': 'old', 'event_position': '1'});
    final paths = <String>[];
    final network = MockClient((request) async {
      paths.add(request.url.path);
      if (request.url.path.endsWith('/session')) {
        return response({'app_id': 'app', 'user_id': 'alice'});
      }
      if (request.url.path.endsWith('/sync')) {
        return response({'code': 'RESET_REQUIRED'}, 410);
      }
      return response(snapshot());
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: scope.environment,
        tokenProvider: () async => 'user',
        client: network,
      ),
      scope: scope,
      deviceId: 'ios',
      store: store,
    );
    await client.initialize(realtime: false);
    expect(client.cache['cursor'], 'fresh');
    expect(paths.where((p) => p.endsWith('/snapshot')).length, 1);
    await client.close(logout: false);
    network.close();
    final other = MockClient(
      (_) async => response({'app_id': 'app', 'user_id': 'bob'}),
    );
    final rejected = ImClient(
      api: HttpApi(
        baseUrl: scope.environment,
        tokenProvider: () async => 'user',
        client: other,
      ),
      scope: scope,
      deviceId: 'ios',
      store: store,
    );
    await expectLater(
      rejected.initialize(realtime: false),
      throwsA(
        isA<ImException>().having((e) => e.code, 'code', 'IDENTITY_MISMATCH'),
      ),
    );
    expect((await store.load(scope))['cursor'], 'fresh');
    await rejected.close(logout: false);
    other.close();
  });
  test('401 最多刷新一次；403 不刷新，关闭后不再发起请求', () async {
    var refreshes = 0, calls = 0;
    final network = MockClient((_) async {
      calls++;
      return response({}, 401);
    });
    final api = HttpApi(
      baseUrl: scope.environment,
      tokenProvider: () async => 'user',
      refreshAuth: () async {
        refreshes++;
      },
      client: network,
    );
    await expectLater(
      api.request('GET', '/state'),
      throwsA(isA<ImException>()),
    );
    expect(refreshes, 1);
    expect(calls, 2);
    api.close();
    await expectLater(api.request('GET', '/state'), throwsStateError);
    expect(calls, 2);
    network.close();
    final forbidden = MockClient((_) async => response({}, 403));
    final denied = HttpApi(
      baseUrl: scope.environment,
      tokenProvider: () async => 'user',
      refreshAuth: () async {
        refreshes++;
      },
      client: forbidden,
    );
    await expectLater(
      denied.request('GET', '/state'),
      throwsA(isA<ImException>()),
    );
    expect(refreshes, 1);
    denied.close();
    forbidden.close();
  });
  test('旧请求不会把令牌更新变成静音/预览偏好重置', () async {
    JsonMap? payload;
    final network = MockClient((request) async {
      payload = jsonDecode(request.body) as JsonMap;
      return response({'ok': true});
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
    await client.updatePushToken('a' * 64);
    expect(payload, {'token': 'a' * 64});
    await client.close(logout: false);
    network.close();
  });
}
