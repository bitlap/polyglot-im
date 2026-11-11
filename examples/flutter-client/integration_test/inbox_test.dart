import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:polyglot_im_flutter_client/polyglot_im_flutter_client.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('真实 iOS SQLite：重启恢复、账户隔离与原生桥接', (tester) async {
    final store = await SqliteStore.openDefault();
    const alice = AccountScope(
      environment: 'test://native',
      appId: 'test',
      userId: 'alice',
    );
    const bob = AccountScope(
      environment: 'test://native',
      appId: 'test',
      userId: 'bob',
    );
    await store.clear(alice);
    await store.clear(bob);
    final device = await store.installationId();
    await store.commit(alice, {'cursor': 'opaque', 'event_position': '42'});
    await store.close();
    final reopened = await SqliteStore.openDefault();
    expect((await reopened.load(alice))['cursor'], 'opaque');
    expect((await reopened.load(bob))['cursor'], isNull);
    expect(await reopened.installationId(), device);
    const native = MethodChannel('polyglot_im/apns');
    expect(
      await native.invokeMethod<String>('currentToken'),
      anyOf(isNull, isA<String>()),
    );
    await reopened.clear(alice);
    await reopened.close();
  });

  testWidgets('Flutter HTTP 连接本地服务并持久恢复消息', (tester) async {
    const base = String.fromEnvironment('IM_BASE_URL');
    const token = String.fromEnvironment('IM_USER_TOKEN');
    if (base.isEmpty || token.isEmpty) return;
    final store = await SqliteStore.openDefault();
    const scope = AccountScope(
      environment: base,
      appId: 'demo',
      userId: 'alice',
    );
    ImClient create() => ImClient(
      api: HttpApi(baseUrl: base, tokenProvider: () async => token),
      scope: scope,
      deviceId: 'ios-integration',
      store: store,
    );
    final client = create();
    await client.initialize(realtime: false);
    expect(client.messages, isNotEmpty);
    final ids = client.messages.map((m) => m.messageId).toSet();
    await client.close(logout: false);
    final second = create();
    await second.initialize(realtime: false);
    expect(
      second.messages.map((m) => m.messageId).toSet().containsAll(ids),
      isTrue,
    );
    await second.markRead();
    expect(second.unreadCount, BigInt.zero);
    await second.close();
    await store.close();
  });
}
