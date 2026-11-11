import 'dart:convert';
import 'dart:io';
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';

Future<void> main(List<String> args) async {
  final config =
      jsonDecode(
            await File(
              args.isEmpty
                  ? File.fromUri(
                      Platform.script.resolve('../../.local-demo/config.json'),
                    ).path
                  : args.first,
            ).readAsString(),
          )
          as JsonMap;
  final user = HttpApi(
    baseUrl: config['base_url'] as String,
    tokenProvider: () async => config['user_token'] as String,
  );
  final service = HttpApi(
    baseUrl: config['base_url'] as String,
    tokenProvider: () async => config['service_token'] as String,
  );
  // 纯 Dart 示例显式使用易失缓存；Flutter 示例使用 SQLite 持久缓存。
  final client = ImClient(
    api: user,
    scope: AccountScope(
      environment: config['base_url'] as String,
      appId: config['app_id'] as String,
      userId: config['user_id'] as String,
    ),
    deviceId: 'dart-demo',
    store: VolatileStore(),
  );
  try {
    await client.initialize(realtime: false);
    final bob = ImClient(
      api: HttpApi(
        baseUrl: config['base_url'] as String,
        tokenProvider: () async => config['bob_token'] as String,
      ),
      scope: AccountScope(
        environment: config['base_url'] as String,
        appId: config['app_id'] as String,
        userId: 'bob',
      ),
      deviceId: 'dart-bob',
      store: VolatileStore(),
    );
    try {
      await bob.initialize(realtime: false);
      final conversation = await client.createDirect('bob');
      final id = conversation['conversation_id'] as String;
      final received = bob.changes
          .firstWhere(
            (state) =>
                (state['conversations'] as Map?)?[id]?['latest_seq'] != '0' &&
                (state['conversations'] as Map?)?[id] != null,
          )
          .timeout(const Duration(seconds: 10));
      bob.startRealtime();
      final chat = await client.sendText(
        id,
        clientMessageId: 'dart-chat',
        text: '你好 😊👍🏽',
      );
      final duplicate = await client.sendText(
        id,
        clientMessageId: 'dart-chat',
        text: '你好 😊👍🏽',
      );
      if (chat.messageId != duplicate.messageId)
        throw StateError('chat idempotency failed');
      await received;
      await bob.sync();
      if (bob.chatUnreadCount != BigInt.one)
        throw StateError('chat unread failed');
      await bob.markConversationRead(id, chat.conversationSeq);
      if (bob.chatUnreadCount != BigInt.zero)
        throw StateError('chat read failed');
      await client.sync();
      final page = await client.conversationHistory(id);
      if (page['conversation']['peer_read_seq'] != chat.conversationSeq)
        throw StateError('chat peer read failed');
    } finally {
      await bob.close();
    }
    final updated = client.changes
        .firstWhere(
          (state) => (state['messages'] as JsonMap).values.any(
            (m) => m['payload']['task_id'] == 'dart-demo-task',
          ),
        )
        .timeout(const Duration(seconds: 10));
    client.startRealtime();
    final sender = NotificationSender(service);
    final first = await sender.send(
      recipientId: 'alice',
      clientMessageId: 'dart-demo-task',
      type: 'task.completed',
      payload: {'task_id': 'dart-demo-task', 'text': '来自 Dart 的通知'},
    );
    final second = await sender.send(
      recipientId: 'alice',
      clientMessageId: 'dart-demo-task',
      type: 'task.completed',
      payload: {'task_id': 'dart-demo-task', 'text': '来自 Dart 的通知'},
    );
    if (first.messageId != second.messageId) {
      throw StateError('idempotency failed');
    }
    await updated;
    await client.markRead();
    if (client.unreadCount != BigInt.zero) throw StateError('read failed');
    stdout.writeln(
      'Dart direct chat/emoji + HTTP/WS + idempotency + read: passed',
    );
  } finally {
    await client.close();
    service.close();
  }
}
