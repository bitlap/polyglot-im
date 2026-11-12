import 'dart:convert';
import 'dart:io';

import 'package:polyglot_im/polyglot_im.dart';
import 'package:polyglot_im/sqlite.dart';
import 'package:test/test.dart';

JsonMap fixture(String name) =>
    jsonDecode(File('../protocol/fixtures/$name').readAsStringSync())
        as JsonMap;

JsonMap message(int id) => {
  ...fixture('message.json'),
  'message_id': 'message-$id',
  'conversation_seq': '$id',
  'type': 'custom.future',
  'payload': {'text': '你好 👨‍👩‍👧‍👦', 'future': 'kept'},
};

void main() {
  const scope = AccountScope(
    environment: 'https://im.example',
    appId: 'app',
    userId: 'alice',
  );
  late Directory directory;
  late String path;
  final stores = <SqliteStore>[];

  Future<SqliteStore> open([String? filename]) async {
    final store = await SqliteStore.open(filename ?? path);
    stores.add(store);
    return store;
  }

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('im-dart-sqlite');
    path = '${directory.path}/账户 缓存.sqlite';
  });
  tearDown(() async {
    for (final store in stores) {
      await store.close();
    }
    stores.clear();
    await directory.delete(recursive: true);
  });

  test('共享契约与大序号落盘，关闭重建后恢复且环境/应用/账号隔离', () async {
    final store = await open();
    final saved = await store.commit(scope, fixture('sync.json'));
    expect(saved['latest_seq'], '9007199254740993');
    expect(await File(path).exists(), isTrue);
    await store.close();
    final reopened = await open();
    expect(await reopened.load(scope), saved);
    const others = [
      AccountScope(environment: 'https://other', appId: 'app', userId: 'alice'),
      AccountScope(
        environment: 'https://im.example',
        appId: 'other',
        userId: 'alice',
      ),
      AccountScope(
        environment: 'https://im.example',
        appId: 'app',
        userId: 'bob',
      ),
    ];
    for (final other in others) {
      expect(await reopened.load(other), emptyCache());
      await reopened.commit(other, {'cursor': other.key});
    }
    await reopened.clear(scope);
    expect(await reopened.load(scope), emptyCache());
    for (final other in others) {
      expect((await reopened.load(other))['cursor'], other.key);
    }
  });

  test('16 个独立连接并发写入不丢消息，关闭一个不影响其他实例', () async {
    final opened = await Future.wait(List.generate(16, (_) => open()));
    await Future.wait([
      for (var i = 0; i < opened.length; i++)
        opened[i].commit(scope, {
          'messages': [message(i + 1)],
          'latest_seq': '${i + 1}',
        }),
    ]);
    await opened.first.close();
    final saved = await opened.last.load(scope);
    expect((saved['messages'] as Map).length, 16);
    expect(saved['latest_seq'], '16');
    expect(saved['messages']['message-1']['payload']['future'], 'kept');
  });

  test('域校验与真实 SQL 写入失败回滚游标，失败后连接继续可用', () async {
    final store = await open();
    final saved = await store.commit(scope, fixture('sync.json'));
    await expectLater(
      store.commit(scope, {
        'cursor': 'invalid',
        'messages': [{}],
      }),
      throwsA(anything),
    );
    await store.database.execute(
      'CREATE TRIGGER reject_cache BEFORE UPDATE ON im_cache '
      "BEGIN SELECT RAISE(ABORT,'test write failure'); END",
    );
    await expectLater(
      store.commit(scope, {'cursor': 'failed'}),
      throwsA(anything),
    );
    expect(await (await open()).load(scope), saved);
    await store.database.execute('DROP TRIGGER reject_cache');
    expect((await store.commit(scope, {'cursor': 'retry'}))['cursor'], 'retry');
  });

  test('快照替换与损坏缓存清理保留其他账号', () async {
    final store = await open();
    const bob = AccountScope(
      environment: 'https://im.example',
      appId: 'app',
      userId: 'bob',
    );
    await store.commit(bob, {'cursor': 'bob'});
    await store.commit(scope, {
      'cursor': 'old',
      'messages': [message(1)],
    });
    final reset = await store.commit(scope, {
      'messages': [message(2)],
    }, reset: true);
    expect((reset['messages'] as Map).keys, ['message-2']);
    expect(reset['cursor'], isNull);
    await store.database.rawUpdate('UPDATE im_cache SET data=? WHERE scope=?', [
      '{broken',
      scope.key,
    ]);
    await expectLater(store.load(scope), throwsFormatException);
    await store.clear(scope);
    expect(await store.load(scope), emptyCache());
    expect((await store.load(bob))['cursor'], 'bob');
  });

  test('WAL 读取在独立写事务期间不申请写锁', () async {
    final store = await open();
    final writer = await open();
    final saved = await store.commit(scope, {'cursor': 'saved'});
    expect(
      (await store.database.rawQuery(
        'PRAGMA journal_mode',
      )).single.values.single,
      'wal',
    );
    expect(
      (await store.database.rawQuery(
        'PRAGMA synchronous',
      )).single.values.single,
      2,
    );
    await writer.database.execute('BEGIN IMMEDIATE');
    try {
      expect(
        await store.load(scope).timeout(const Duration(seconds: 2)),
        saved,
      );
    } finally {
      await writer.database.execute('ROLLBACK');
    }
  });

  test('关闭等待已接受的写入，重复关闭安全且已关闭实例拒绝访问', () async {
    final store = await open();
    final write = store.commit(scope, {'cursor': 'before-close'});
    final closed = store.close();
    await write;
    await closed;
    await store.close();
    expect(() => store.load(scope), throwsStateError);
    expect(() => store.commit(scope, {}), throwsStateError);
    expect((await (await open()).load(scope))['cursor'], 'before-close');
  });

  test('拒绝非持久路径，打开失败不破坏后续连接', () async {
    for (final invalid in [
      '',
      ':memory:',
      'file::memory:',
      'file:cache?mode=memory',
    ]) {
      await expectLater(SqliteStore.open(invalid), throwsArgumentError);
    }
    await expectLater(SqliteStore.open(directory.path), throwsA(anything));
    final store = await open('${directory.path}/nested/im.sqlite');
    expect((await store.commit(scope, {'cursor': 'ok'}))['cursor'], 'ok');
  });
}
