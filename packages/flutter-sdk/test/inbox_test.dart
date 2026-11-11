import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_flutter_sdk/polyglot_im_flutter_sdk.dart';

void main() {
  testWidgets('点击成功打开资源后，向服务端提交所点击序号', (tester) async {
    String? read;
    var opened = false;
    final message = <String, dynamic>{
      'schema_version': 1,
      'message_id': 'm1',
      'conversation_id': 'notice',
      'conversation_seq': '1',
      'type': 'system.notice',
      'payload': {'title': '测试通知', 'text': '正文'},
      'created_at': '2026-11-11T00:00:00Z',
    };
    JsonMap state() => {
      'cursor': 'opaque',
      'event_position': read == null ? '1' : '2',
      'latest_seq': '1',
      'read_seq': read ?? '0',
      'unread_count': read == null ? '1' : '0',
      'conversation_id': 'notice',
    };
    final network = MockClient((request) async {
      final path = request.url.path;
      if (path.endsWith('/session')) {
        return http.Response(
          jsonEncode({'app_id': 'app', 'user_id': 'alice'}),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      if (path.endsWith('/snapshot')) {
        return http.Response(
          jsonEncode({
            ...state(),
            'messages': [message],
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      if (path.endsWith('/messages')) {
        return http.Response(
          jsonEncode({
            'items': [message],
            'has_more': false,
            'next_before_seq': '1',
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }
      if (path.endsWith('/read')) {
        read = (jsonDecode(request.body) as JsonMap)['up_to_seq'] as String;
      }
      return http.Response(
        jsonEncode({...state(), 'events': [], 'has_more': false}),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: 'https://im.example',
        tokenProvider: () async => 'user',
        client: network,
      ),
      scope: const AccountScope(
        environment: 'https://im.example',
        appId: 'app',
        userId: 'alice',
      ),
      deviceId: 'ios',
      store: VolatileStore(),
    );
    await client.initialize(realtime: false);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ImInbox(
            client: client,
            onOpen: (_) async {
              opened = true;
            },
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('测试通知'));
    await tester.pumpAndSettle();
    expect(opened, isTrue);
    expect(read, '1');
    expect(client.unreadCount, BigInt.zero);
    await tester.pumpWidget(const SizedBox());
    await client.close(logout: false);
    network.close();
  });
}
