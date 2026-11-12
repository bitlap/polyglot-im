import 'dart:convert';

import 'package:postgres/postgres.dart' as pg;

import '../../common/types.dart';
import '../../domain/account_scope.dart';
import '../../domain/cache.dart';
import '../../ports/local_store.dart';

/// 可注入的 PostgreSQL 消息缓存；默认借用宿主池，与业务 Outbox 分开。
class PostgresStore implements LocalStore {
  /// namespace 隔离缓存使用者；不接管传入连接池，需显式 initialize 建表。
  PostgresStore(
    this.pool, {
    required this.namespace,
    this.schema = 'snap_flow',
    this.lockTimeout = const Duration(seconds: 15),
  }) {
    if (namespace.trim().isEmpty || namespace.length > 200) {
      throw ArgumentError('namespace must contain 1..200 characters');
    }
    if (!RegExp(r'^[a-z_][a-z0-9_]{0,62}$').hasMatch(schema)) {
      throw ArgumentError('schema must be a PostgreSQL identifier');
    }
    if (lockTimeout.inMilliseconds <= 0) {
      throw ArgumentError('lockTimeout must be positive');
    }
  }

  /// 借用或由 open 创建的池；不在核心创建全局池。
  final pg.Pool<void> pool;
  final String namespace, schema;
  final Duration lockTimeout;
  bool _ownsPool = false;
  Future<void>? _closing;
  String get _table => '$schema.im_client_cache';

  /// 显式 URL 创建并拥有池，初始化失败关闭池；TLS/池大小/超时由 URL 配置。
  static Future<PostgresStore> open(
    String url, {
    required String namespace,
    String schema = 'snap_flow',
    Duration lockTimeout = const Duration(seconds: 15),
  }) async {
    final uri = Uri.tryParse(url);
    if (uri == null ||
        !{'postgres', 'postgresql'}.contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.path.length < 2) {
      throw ArgumentError('explicit PostgreSQL URL required');
    }
    final pool = pg.Pool<void>.withUrl(url);
    try {
      final store = PostgresStore(
        pool,
        namespace: namespace,
        schema: schema,
        lockTimeout: lockTimeout,
      );
      store._ownsPool = true;
      await store.initialize();
      return store;
    } catch (_) {
      await pool.close();
      rethrow;
    }
  }

  void _ensureOpen() {
    if (_closing != null) throw StateError('PostgreSQL store is closed');
  }

  Future<T> _transaction<T>(Future<T> Function(pg.TxSession) run) {
    _ensureOpen();
    return pool.runTx(
      (tx) async {
        await tx.execute(
          pg.Sql.named("SELECT set_config('lock_timeout', @timeout, true)"),
          parameters: {'timeout': '${lockTimeout.inMilliseconds}ms'},
        );
        return run(tx);
      },
      settings: pg.TransactionSettings(
        isolationLevel: pg.IsolationLevel.readCommitted,
      ),
    );
  }

  /// 显式创建缓存表，使用跨实例 DDL 锁，失败回滚；不修改 Outbox。
  Future<void> initialize() => _transaction((tx) async {
    await tx.execute('SELECT pg_advisory_xact_lock(674239012)');
    await tx.execute('CREATE SCHEMA IF NOT EXISTS $schema');
    await tx.execute(
      'CREATE TABLE IF NOT EXISTS $_table('
      'namespace TEXT NOT NULL,scope TEXT NOT NULL,data TEXT NOT NULL,'
      'PRIMARY KEY(namespace,scope))',
    );
  });

  Map<String, dynamic> _parameters(AccountScope scope) => {
    'namespace': namespace,
    'scope': scope.key,
  };

  Future<JsonMap> _read(pg.Session session, AccountScope scope) async {
    final rows = await session.execute(
      pg.Sql.named(
        'SELECT data FROM $_table WHERE namespace=@namespace AND scope=@scope',
      ),
      parameters: _parameters(scope),
    );
    return rows.isEmpty
        ? emptyCache()
        : jsonDecode(rows.single[0] as String) as JsonMap;
  }

  Future<void> _lock(pg.TxSession tx, AccountScope scope) async {
    await tx.execute(
      pg.Sql.named(
        'SELECT pg_advisory_xact_lock(hashtextextended(@key,674239012))',
      ),
      parameters: {'key': '$namespace\n${scope.key}'},
    );
  }

  /// 不占写锁读取已提交缓存，未保存的作用域返回空缓存。
  @override
  Future<JsonMap> load(AccountScope scope) {
    _ensureOpen();
    return _read(pool, scope);
  }

  /// 跨实例串行归并，消息/会话/游标同事务提交；失败回滚，reset 用快照重建。
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) => _transaction((tx) async {
    await _lock(tx, scope);
    final value = applyPage(await _read(tx, scope), page, reset: reset);
    await tx.execute(
      pg.Sql.named(
        'INSERT INTO $_table(namespace,scope,data) VALUES(@namespace,@scope,@data) '
        'ON CONFLICT(namespace,scope) DO UPDATE SET data=excluded.data',
      ),
      parameters: {..._parameters(scope), 'data': jsonEncode(value)},
    );
    return value;
  });

  /// 与提交共用作用域锁，只清理目标缓存；损坏 JSON 也可清理。
  @override
  Future<void> clear(AccountScope scope) => _transaction((tx) async {
    await _lock(tx, scope);
    await tx.execute(
      pg.Sql.named(
        'DELETE FROM $_table WHERE namespace=@namespace AND scope=@scope',
      ),
      parameters: _parameters(scope),
    );
  });

  /// 停止使用本存储，仅关闭 open 创建的自有池；重复调用复用关闭结果。
  Future<void> close() =>
      _closing ??= _ownsPool ? pool.close() : Future<void>.value();
}
