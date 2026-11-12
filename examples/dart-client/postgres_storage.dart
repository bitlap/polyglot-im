import 'dart:convert';
import 'dart:io';

import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';
import 'package:polyglot_im_dart_client/postgres.dart';
import 'package:postgres/postgres.dart' as pg;

/// 在 demo-server 的独立临时 PostgreSQL 验证缓存，不接受生产配置或打印 URL。
Future<void> main(List<String> args) async {
  final config = jsonDecode(await File(args.single).readAsString()) as JsonMap;
  final url = config['cache_postgres_url'] as String;
  final uri = Uri.parse(url);
  if (uri.host != '127.0.0.1' || uri.userInfo != 'postgres') {
    throw ArgumentError('temporary local demo PostgreSQL required');
  }
  final schema = 'im_test_dart_${pid}_${DateTime.now().microsecondsSinceEpoch}';
  const scope = AccountScope(
    environment: 'https://im.example',
    appId: 'app',
    userId: 'alice',
  );
  final pool = pg.Pool<void>.withUrl(url);
  final stores = <PostgresStore>[];
  void check(bool condition, String reason) {
    if (!condition) throw StateError(reason);
  }

  final fixtureRoot = File.fromUri(
    Platform.script.resolve('../../protocol/fixtures/'),
  ).path;
  JsonMap fixture(String name) =>
      jsonDecode(File('$fixtureRoot/$name').readAsStringSync()) as JsonMap;
  JsonMap message(int id) => {
    ...fixture('message.json'),
    'message_id': 'pg-message-$id',
    'conversation_seq': '$id',
    'type': 'custom.future',
    'payload': {'text': '你好 👨‍👩‍👧‍👦', 'future': 'kept'},
  };
  PostgresStore borrowed(
    String namespace, {
    Duration lockTimeout = const Duration(seconds: 15),
  }) {
    final value = PostgresStore(
      pool,
      namespace: namespace,
      schema: schema,
      lockTimeout: lockTimeout,
    );
    stores.add(value);
    return value;
  }

  try {
    final store = borrowed('device');
    await store.initialize();
    final saved = await store.commit(scope, fixture('sync.json'));
    check(saved['latest_seq'] == '9007199254740993', 'large sequence changed');
    final reopened = await PostgresStore.open(
      url,
      namespace: 'device',
      schema: schema,
    );
    stores.add(reopened);
    check(
      jsonEncode(await reopened.load(scope)) == jsonEncode(saved),
      'restart cache differs',
    );
    await reopened.close();
    await store.commit(scope, {'cursor': 'after-other-close'});
    final other = borrowed('other');
    check((await other.load(scope))['cursor'] == null, 'namespace leaked');
    await other.commit(scope, {'cursor': 'other'});
    const scopes = [
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
    for (final value in scopes) {
      check((await store.load(value))['cursor'] == null, 'identity leaked');
      await store.commit(value, {'cursor': value.key});
    }
    await store.clear(scope);
    final peers = await Future.wait(
      List.generate(16, (_) async {
        final value = await PostgresStore.open(
          url,
          namespace: 'device',
          schema: schema,
        );
        stores.add(value);
        return value;
      }),
    );
    await Future.wait([
      for (var i = 0; i < 16; i++)
        peers[i].commit(scope, {
          'messages': [message(i + 1)],
          'latest_seq': '${i + 1}',
        }),
    ]);
    final parallel = await store.load(scope);
    check(
      (parallel['messages'] as Map).length == 16 &&
          parallel['latest_seq'] == '16',
      'parallel updates lost',
    );
    check(
      parallel['messages']['pg-message-1']['payload']['future'] == 'kept',
      'unknown payload lost',
    );
    await pool.execute(
      'CREATE FUNCTION $schema.reject_cache() RETURNS trigger LANGUAGE plpgsql AS '
      "\$\$ BEGIN RAISE EXCEPTION 'test write failure'; END \$\$",
    );
    await pool.execute(
      'CREATE TRIGGER reject_cache BEFORE UPDATE ON $schema.im_client_cache '
      'FOR EACH ROW EXECUTE FUNCTION $schema.reject_cache()',
    );
    var rejected = false;
    try {
      await store.commit(scope, {'cursor': 'failed'});
    } on pg.ServerException {
      rejected = true;
    }
    check(rejected, 'SQL failure accepted');
    check(
      jsonEncode(await store.load(scope)) == jsonEncode(parallel),
      'failed SQL changed cursor',
    );
    await pool.execute('DROP TRIGGER reject_cache ON $schema.im_client_cache');
    final reset = await store.commit(scope, {
      'messages': [message(20)],
    }, reset: true);
    check(
      (reset['messages'] as Map).length == 1 && reset['cursor'] == null,
      'snapshot reset failed',
    );
    await pool.execute(
      pg.Sql.named(
        'UPDATE $schema.im_client_cache SET data=@data WHERE namespace=@namespace AND scope=@scope',
      ),
      parameters: {
        'data': '{broken',
        'namespace': 'device',
        'scope': scope.key,
      },
    );
    var corrupt = false;
    try {
      await store.load(scope);
    } on FormatException {
      corrupt = true;
    }
    check(corrupt, 'corrupt cache accepted');
    await store.clear(scope);
    await store.commit(scope, {'cursor': 'saved'});
    final limited = borrowed(
      'device',
      lockTimeout: const Duration(milliseconds: 100),
    );
    await pool.runTx((tx) async {
      await tx.execute(
        pg.Sql.named(
          'SELECT pg_advisory_xact_lock(hashtextextended(@key,674239012))',
        ),
        parameters: {'key': 'device\n${scope.key}'},
      );
      check(
        (await store
                .load(scope)
                .timeout(const Duration(seconds: 2)))['cursor'] ==
            'saved',
        'read acquired write lock',
      );
      var timeout = false;
      try {
        await limited.commit(scope, {'cursor': 'timeout'});
      } on pg.ServerException catch (error) {
        timeout = error.code == '55P03';
      }
      check(timeout, 'lock timeout not enforced');
    });
    check(
      (await store.load(scope))['cursor'] == 'saved',
      'timeout changed cache',
    );
    await Future.wait([
      store.clear(scope),
      peers.first.commit(scope, {'cursor': 'racing'}),
    ]);
    await store.commit(scope, {'cursor': 'retry'});
    for (final value in scopes) {
      check(
        (await store.load(value))['cursor'] == value.key,
        'clear changed other scope',
      );
    }
    check(
      (await other.load(scope))['cursor'] == 'other',
      'clear changed other namespace',
    );
    await other.close();
    check(
      (await pool.execute('SELECT 1')).single[0] == 1,
      'borrowed pool closed',
    );
    var closed = false;
    try {
      await other.load(scope);
    } on StateError {
      closed = true;
    }
    check(closed, 'closed store reused');
    final actualScope = AccountScope(
      environment: config['base_url'] as String,
      appId: config['app_id'] as String,
      userId: config['user_id'] as String,
    );
    final network = borrowed('network');
    final client = ImClient(
      api: HttpApi(
        baseUrl: config['base_url'] as String,
        tokenProvider: () async => config['user_token'] as String,
      ),
      scope: actualScope,
      deviceId: 'dart-postgres-cache',
      store: network,
    );
    try {
      await client.initialize(realtime: false);
      await client.sync();
      check(
        (client.cache['messages'] as Map).isNotEmpty,
        'HTTP sync did not persist messages',
      );
      final restored = await network.load(actualScope);
      check(
        jsonEncode(restored) == jsonEncode(client.cache),
        'HTTP cache differs',
      );
    } finally {
      await client.close(logout: false);
    }
    stdout.writeln(
      'Dart PostgreSQL cache: HTTP sync/restart/isolation/16 pools/rollback/reset/clear/lock timeout/ownership passed',
    );
  } finally {
    for (final store in stores) {
      await store.close();
    }
    try {
      await pool.execute('DROP SCHEMA IF EXISTS $schema CASCADE');
    } finally {
      await pool.close();
    }
  }
}
