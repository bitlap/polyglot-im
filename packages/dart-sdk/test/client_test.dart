import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
import 'package:test/test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'dart:io';
import 'dart:convert';

void main() {
  test('网关 HTML 错误可重试，权限错误不重试', () async {
    var calls = 0;
    final network = MockClient(
      (request) async => ++calls == 1
          ? http.Response('<html>unavailable</html>', 503)
          : http.Response('{"ok":true}', 200),
    );
    final api = HttpApi(
      baseUrl: 'https://im.example',
      tokenProvider: () async => 'token',
      client: network,
    );
    expect(await api.request('GET', '/state'), {'ok': true});
    expect(calls, 2);
    api.close();
    network.close();
    final forbidden = MockClient((_) async => http.Response('forbidden', 403));
    final rejected = HttpApi(
      baseUrl: 'https://im.example',
      tokenProvider: () async => 'token',
      client: forbidden,
    );
    await expectLater(
      rejected.request('GET', '/state'),
      throwsA(isA<ImException>().having((e) => e.status, 'status', 403)),
    );
    rejected.close();
    forbidden.close();
  });
  test('共享增量契约保留大序号，失败不修改原缓存', () {
    final page =
        jsonDecode(File('../../protocol/fixtures/sync.json').readAsStringSync())
            as JsonMap;
    final state = applyPage(emptyCache(), page);
    expect(state['latest_seq'], '9007199254740993');
    expect(state['unread_count'], '1');
    expect(
      () => applyPage(state, {
        'cursor': 'must-not-commit',
        'messages': [{}],
      }),
      throwsA(anything),
    );
    expect(state['cursor'], 'opaque-cursor');
  });
  test('旧页不回退游标，重复页幂等', () {
    final page = <String, dynamic>{'event_position': '2', 'cursor': 'opaque'};
    final state = applyPage(emptyCache(), page);
    expect(applyPage(state, {'event_position': '1', 'cursor': 'old'}), state);
    expect(applyPage(state, page), state);
  });
  test('缓存按环境、应用和用户隔离', () async {
    final store = VolatileStore();
    const alice = AccountScope(
      environment: 'https://im.example',
      appId: 'app',
      userId: 'alice',
    );
    const bob = AccountScope(
      environment: 'https://im.example',
      appId: 'app',
      userId: 'bob',
    );
    await store.commit(alice, {'cursor': 'opaque'});
    expect((await store.load(bob))['cursor'], isNull);
    expect((await store.load(alice))['cursor'], 'opaque');
  });
}
