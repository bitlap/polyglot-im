import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
import 'package:test/test.dart';

void main() {
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
