import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:polyglot_im_flutter_client/polyglot_im_flutter_client.dart';

void main() {
  testWidgets('文字/表情发送失败后原文重试复用幂等键，显示消息且没有附件入口', (tester) async {
    final sent = <JsonMap>[];
    var delivered = false;
    var read = '0';
    final message = <String, dynamic>{
      'schema_version': 1,
      'message_id': 'chat-1',
      'conversation_id': 'direct-1',
      'conversation_seq': '1',
      'type': 'chat.text',
      'payload': {'sender_id': 'alice', 'text': '你好 😊👍🏽'},
      'created_at': '2026-11-11T00:00:00Z',
    };
    JsonMap conversation() => {
      'conversation_id': 'direct-1',
      'kind': 'direct',
      'member_ids': ['alice', 'bob'],
      'revision': read == '1'
          ? '3'
          : delivered
          ? '2'
          : '1',
      'latest_seq': delivered ? '1' : '0',
      'read_seq': read,
      'peer_read_seq': '0',
      'unread_count': '0',
      'messages': [if (delivered) message],
    };
    final network = MockClient((request) async {
      JsonMap result = {};
      var status = 200;
      if (request.url.path.endsWith('/messages') && request.method == 'POST') {
        sent.add(jsonDecode(request.body) as JsonMap);
        if (sent.length == 1) {
          status = 422;
          result = {'code': 'TEST_RETRY', 'message': '请重试'};
        } else {
          delivered = true;
          result = message;
          status = 201;
        }
      } else if (request.url.path.endsWith('/messages')) {
        result = {
          'conversation': conversation(),
          'items': [],
          'has_more': false,
          'next_before_seq': null,
        };
      } else if (request.url.path.endsWith('/read')) {
        read = (jsonDecode(request.body) as JsonMap)['up_to_seq'] as String;
        result = conversation();
      } else {
        result = {
          'cursor': 'opaque',
          'event_position': delivered ? '1' : '0',
          'latest_seq': '0',
          'read_seq': '0',
          'unread_count': '0',
          'events': [],
          'has_more': false,
          'conversations': [conversation()],
        };
      }
      return http.Response(
        jsonEncode(result),
        status,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: 'https://im.example',
        tokenProvider: () async => 'alice',
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
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ImDirectChat(client: client, conversationId: 'direct-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '你好 😊👍🏽');
    await tester.tap(find.text('发送'));
    await tester.pumpAndSettle();
    expect(find.textContaining('请重试'), findsOneWidget);
    await tester.tap(find.text('发送'));
    await tester.pumpAndSettle();
    expect(sent.length, 2);
    expect(sent[0]['client_message_id'], sent[1]['client_message_id']);
    expect(sent[1]['payload'], {'text': '你好 😊👍🏽'});
    expect(find.text('你好 😊👍🏽'), findsOneWidget);
    expect(find.byIcon(Icons.image), findsNothing);
    await tester.pumpWidget(const SizedBox());
    await client.close(logout: false);
    network.close();
  });
  testWidgets('后台收到的消息不标记已读，恢复显示后才提交', (tester) async {
    var reads = 0;
    final message = <String, dynamic>{
      'schema_version': 1,
      'message_id': 'incoming',
      'conversation_id': 'direct-1',
      'conversation_seq': '1',
      'type': 'chat.text',
      'payload': {'sender_id': 'bob', 'text': '后台消息'},
      'created_at': '2026-11-11T00:00:00Z',
    };
    JsonMap conversation() => {
      'conversation_id': 'direct-1',
      'kind': 'direct',
      'member_ids': ['alice', 'bob'],
      'revision': reads == 0 ? '2' : '3',
      'latest_seq': '1',
      'read_seq': reads == 0 ? '0' : '1',
      'peer_read_seq': '0',
      'unread_count': reads == 0 ? '1' : '0',
      'messages': [message],
    };
    final network = MockClient((request) async {
      JsonMap result;
      if (request.url.path.endsWith('/read')) {
        ++reads;
        result = conversation();
      } else if (request.url.path.endsWith('/messages')) {
        result = {
          'conversation': conversation(),
          'items': [message],
          'has_more': false,
          'next_before_seq': '1',
        };
      } else {
        result = {
          'cursor': 'opaque',
          'event_position': reads == 0 ? '2' : '3',
          'latest_seq': '0',
          'read_seq': '0',
          'unread_count': '0',
          'chat_unread_count': reads == 0 ? '1' : '0',
          'conversations': [conversation()],
          'events': [],
          'has_more': false,
        };
      }
      return http.Response(
        jsonEncode(result),
        200,
        headers: {'content-type': 'application/json; charset=utf-8'},
      );
    });
    final client = ImClient(
      api: HttpApi(
        baseUrl: 'https://im.example',
        tokenProvider: () async => 'alice',
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
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: ImDirectChat(client: client, conversationId: 'direct-1'),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(reads, 0);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(reads, 1);
    expect(client.chatUnreadCount, BigInt.zero);
    await tester.pumpWidget(const SizedBox());
    await client.close(logout: false);
    network.close();
  });
}
