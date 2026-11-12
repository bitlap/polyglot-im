import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';
import 'package:test/test.dart';

void main() {
  test('共享业务身份契约直接复用不透明 Bearer 并支持轮换', () async {
    final identity =
        jsonDecode(
              File(
                '../protocol/fixtures/introspection.json',
              ).readAsStringSync(),
            )
            as JsonMap;
    var token = 'opaque-first';
    final seen = <String?>[];
    final network = MockClient((request) async {
      seen.add(request.headers['Authorization']);
      return http.Response(
        jsonEncode({'code': 200, 'message': 'success', 'data': identity}),
        200,
      );
    });
    final api = HttpApi(
      baseUrl: 'https://im.example',
      tokenProvider: () async => token,
      client: network,
    );
    expect((await api.request('POST', '/session'))['user_id'], 'alice');
    token = 'opaque-rotated';
    await api.request('GET', '/state');
    expect(seen, ['Bearer opaque-first', 'Bearer opaque-rotated']);
    api.close();
    network.close();
  });
  test('共享 HTTP 信封、HTTP 201 和稳定业务错误码', () async {
    var status = 201;
    var body = File(
      '../protocol/fixtures/http-success.json',
    ).readAsStringSync();
    final network = MockClient((request) async {
      expect(request.url.path, '/api/v1/im/notifications');
      return http.Response(
        body,
        status,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    final api = HttpApi(
      baseUrl: 'https://im.example',
      tokenProvider: () async => 'test',
      client: network,
    );
    expect(
      await api.request('POST', '/notifications'),
      (jsonDecode(body) as JsonMap)['data'],
    );
    status = 409;
    body = File('../protocol/fixtures/http-error.json').readAsStringSync();
    await expectLater(
      api.request('POST', '/notifications'),
      throwsA(
        isA<ImException>()
            .having((e) => e.code, 'code', 'IDEMPOTENCY_CONFLICT')
            .having((e) => e.status, 'status', 409),
      ),
    );
    status = 200;
    body = '{"code":200,"message":"success","data":null}';
    await expectLater(
      api.request('POST', '/notifications'),
      throwsFormatException,
    );
    api.close();
    network.close();
  });
}
