import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

class NotificationApi implements RemoteApi {
  final rows = <JsonMap>[
    for (var i = 1; i <= 3; i++)
      {
        'schema_version': 1,
        'message_id': 'm$i',
        'conversation_id': 'notice',
        'conversation_seq': '$i',
        'type': 'system.notice',
        'payload': {'title': '通知$i', 'text': '正文$i'},
        'created_at': '2026-11-13T00:00:00Z',
      },
  ];
  final events = <JsonMap>[];
  final calls = <String>[];
  var revision = 3;
  bool fail = false;
  @override
  String get baseUrl => 'https://im.test';
  @override
  void bindScope(AccountScope scope) {}
  @override
  void close() {}
  @override
  Future<JsonMap> request(String method, String path, {JsonMap? body}) async {
    calls.add('$method $path');
    if (path == '/session') return {'app_id': 'app', 'user_id': 'alice'};
    if (path.startsWith('/notifications/')) {
      if (fail) throw StateError('offline');
      for (final id in body!['message_ids'] as List) {
        final row = rows.firstWhere((m) => m['message_id'] == id);
        row.addAll({
          'is_read': true,
          'is_deleted': path.endsWith('/delete'),
          'state_version': '${++revision}',
        });
        events.add({
          'type': 'notification.updated',
          'data': Map<String, dynamic>.of(row),
        });
      }
    }
    if (path.startsWith('/messages?')) {
      return {
        'items': rows.where((m) => m['is_deleted'] != true).toList(),
        'has_more': false,
        'event_position': '$revision',
      };
    }
    return {
      'cursor': 'c$revision',
      'event_position': '$revision',
      'latest_seq': '3',
      'read_seq': '0',
      'unread_count':
          '${rows.where((m) => m['is_read'] != true && m['is_deleted'] != true).length}',
      'messages': path == '/snapshot'
          ? rows.where((m) => m['is_deleted'] != true).toList()
          : [],
      'events': events,
      'has_more': false,
    };
  }
}

void main() {
  Future<ImClient> create(NotificationApi api) async {
    final client = ImClient(
      api: api,
      scope: const AccountScope(
        environment: 'https://im.test',
        appId: 'app',
        userId: 'alice',
      ),
      deviceId: 'ios',
      store: VolatileStore(),
    );
    await client.initialize(realtime: false);
    return client;
  }

  Future<void> show(WidgetTester tester, ImClient client) async {
    var selecting = false;
    await tester.pumpWidget(
      MaterialApp(
        home: StatefulBuilder(
          builder: (context, setState) => Scaffold(
            appBar: AppBar(
              actions: [
                TextButton(
                  onPressed: () => setState(() => selecting = !selecting),
                  child: Text(selecting ? '取消' : '选择'),
                ),
              ],
            ),
            body: ImInbox(
              client: client,
              selectionMode: selecting,
              onSelectionDone: () => setState(() => selecting = false),
              onOpen: (_) async {},
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('点击只读一条，左滑已读/删除可靠更新且不跳过更早未读', (tester) async {
    final api = NotificationApi();
    final active = await create(api);
    await show(tester, active);
    await tester.tap(find.text('通知3'));
    await tester.pumpAndSettle();
    expect(active.unreadCount, BigInt.two);
    expect(
      active.messages.firstWhere((m) => m.messageId == 'm2').isRead,
      false,
    );
    expect(api.calls.where((call) => call == 'PUT /read'), isEmpty);
    await tester.drag(find.text('通知2'), const Offset(-230, 0));
    await tester.pumpAndSettle();
    expect(find.text('删除'), findsOneWidget);
    await tester.tap(find.text('已读'));
    await tester.pumpAndSettle();
    expect(active.unreadCount, BigInt.one);
    await tester.drag(find.text('通知1'), const Offset(-230, 0));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除'));
    await tester.pumpAndSettle();
    expect(find.text('通知1'), findsNothing);
    expect(active.unreadCount, BigInt.zero);
    expect(active.messages.length, 2);
    await tester.pumpWidget(const SizedBox());
    await active.close(logout: false);
  });
  testWidgets('选择后批量已读、批量删除；失败保留选择与消息', (tester) async {
    final api = NotificationApi();
    final active = await create(api);
    await show(tester, active);
    await tester.tap(find.text('选择'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全选'));
    await tester.pumpAndSettle();
    expect(find.text('已选择 3 条'), findsOneWidget);
    api.fail = true;
    await tester.tap(find.text('删除（3）'));
    await tester.pumpAndSettle();
    expect(find.text('删除失败，请重试'), findsOneWidget);
    expect(active.messages.length, 3);
    expect(find.text('已选择 3 条'), findsOneWidget);
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    api.fail = false;
    await tester.tap(find.text('已读（3）'));
    await tester.pumpAndSettle();
    expect(active.unreadCount, BigInt.zero);
    expect(find.text('选择'), findsOneWidget);
    await tester.tap(find.text('选择'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('全选'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('删除（3）'));
    await tester.pumpAndSettle();
    expect(active.messages, isEmpty);
    expect(find.text('暂无消息'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await active.close(logout: false);
  });
}
