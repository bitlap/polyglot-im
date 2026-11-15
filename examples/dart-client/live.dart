import 'dart:convert';
import 'dart:io';
import 'package:polyglot_im/polyglot_im.dart';
import 'package:polyglot_im/sqlite.dart';

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
  final directory = await Directory.systemTemp.createTemp('im-dart-live');
  final path = '${directory.path}/im.sqlite';
  final store = await SqliteCache.open(path);
  final scope = AccountScope(
    environment: config['base_url'] as String,
    appId: config['app_id'] as String,
    userId: config['user_id'] as String,
  );
  final client = ImClient(
    api: user,
    scope: scope,
    deviceId: 'dart-demo',
    cachePersistence: store,
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
      cachePersistence: store,
    );
    late String chatId;
    late String conversationId;
    try {
      await bob.initialize(realtime: false);
      final conversation = await client.createDirect('bob');
      final id = conversation['conversation_id'] as String;
      conversationId = id;
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
      chatId = chat.messageId;
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
    // 通知由业务后端生产；这里只验证用户历史及已读。
    await client.history();
    await client.markRead();
    if (client.unreadCount != BigInt.zero) throw StateError('read failed');
    await client.close();
    await store.close();
    final reopened = await SqliteCache.open(path);
    try {
      final restored = await reopened.load(scope);
      if (!((restored['conversations'] as Map)[conversationId]['messages']
              as Map)
          .containsKey(chatId)) {
        throw StateError('SQLite restart cache failed');
      }
    } finally {
      await reopened.close();
    }
    stdout.writeln(
      'Dart SQLite restart + direct chat/emoji + HTTP/WS + idempotency + read: passed',
    );
  } finally {
    await client.close();
    await store.close();
    await directory.delete(recursive: true);
  }
}
