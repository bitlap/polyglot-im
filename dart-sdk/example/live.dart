import 'dart:convert';
import 'dart:io';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

Future<void> main(List<String> args) async {
  final config =
      jsonDecode(
            await File(
              args.isEmpty ? '../.local-demo/config.json' : args.first,
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
    stdout.writeln('Dart HTTP/WS + idempotency + read: passed');
  } finally {
    await client.close();
    service.close();
  }
}
