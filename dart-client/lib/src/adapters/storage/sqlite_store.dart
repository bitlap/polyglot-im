import 'dart:convert';
import 'dart:io';

import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'package:synchronized/synchronized.dart';

import '../../common/types.dart';
import '../../domain/account_scope.dart';
import '../../domain/cache.dart';
import '../../ports/local_store.dart';

/// 同一 Dart isolate 内共享文件写锁，随最后一个连接关闭释放。
class _FileGate {
  final lock = Lock();
  int references = 0;
}

/// Dart VM 文件缓存；后台 isolate 执行 SQL，消息、会话与游标同事务提交。
class SqliteStore implements LocalStore {
  SqliteStore._(this.database, this._path, this._gate);

  static final _gates = <String, _FileGate>{};

  /// 当前实例独占的连接；仅用于宿主诊断，生命周期由 close 管理。
  final Database database;
  final String _path;
  final _FileGate _gate;
  Future<void>? _closing;

  static void _release(String path, _FileGate gate) {
    if (--gate.references == 0) _gates.remove(path);
  }

  /// 打开普通持久文件，自动创建父目录；拒绝空路径、内存数据库和 file: URI。
  ///
  /// 每次打开独立连接，调用方应在客户端停止后 close；不修改全局数据库工厂。
  static Future<SqliteStore> open(String path) async {
    if (path.isEmpty || path == ':memory:' || path.startsWith('file:')) {
      throw ArgumentError.value(
        path,
        'path',
        'persistent database path required',
      );
    }
    final input = p.normalize(p.absolute(path));
    await Directory(p.dirname(input)).create(recursive: true);
    // 统一符号链接别名，避免同文件连接在共享 SQL isolate 上互相等待。
    final absolute = await File(input).exists()
        ? await File(input).resolveSymbolicLinks()
        : p.join(
            await Directory(p.dirname(input)).resolveSymbolicLinks(),
            p.basename(input),
          );
    final gate = _gates.putIfAbsent(absolute, _FileGate.new);
    gate.references++;
    try {
      sqfliteFfiInit();
      final db = await gate.lock.synchronized(() async {
        final connection = await databaseFactoryFfi.openDatabase(
          absolute,
          options: OpenDatabaseOptions(singleInstance: false),
        );
        try {
          await connection.execute('PRAGMA busy_timeout=15000');
          await connection.rawQuery('PRAGMA journal_mode=WAL');
          await connection.execute('PRAGMA synchronous=FULL');
          await connection.execute(
            'CREATE TABLE IF NOT EXISTS im_cache('
            'scope TEXT PRIMARY KEY, data TEXT NOT NULL)',
          );
          return connection;
        } catch (_) {
          await connection.close();
          rethrow;
        }
      });
      return SqliteStore._(db, absolute, gate);
    } catch (_) {
      _release(absolute, gate);
      rethrow;
    }
  }

  void _ensureOpen() {
    if (_closing != null) throw StateError('SQLite store is closed');
  }

  Future<JsonMap> _load(DatabaseExecutor db, AccountScope scope) async {
    final rows = await db.rawQuery('SELECT data FROM im_cache WHERE scope=?', [
      scope.key,
    ]);
    return rows.isEmpty
        ? emptyCache()
        : jsonDecode(rows.single['data'] as String) as JsonMap;
  }

  /// 读取指定账号/环境缓存；WAL 普通读取不占写事务。
  @override
  Future<JsonMap> load(AccountScope scope) {
    _ensureOpen();
    return _load(database, scope);
  }

  /// 在写事务中归并并提交消息、会话与游标；失败回滚，reset 用快照重建。
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) => _write((txn) async {
    final value = applyPage(await _load(txn, scope), page, reset: reset);
    await txn.rawInsert(
      'INSERT INTO im_cache(scope,data) VALUES(?,?) '
      'ON CONFLICT(scope) DO UPDATE SET data=excluded.data',
      [scope.key, jsonEncode(value)],
    );
    return value;
  });

  Future<T> _write<T>(Future<T> Function(Transaction) action) {
    _ensureOpen();
    // 多连接共享同一后台 SQL isolate：先串行化写事务，避免 BUSY 阻塞其余连接提交。
    return _gate.lock.synchronized(
      () => database.transaction(action, exclusive: true),
    );
  }

  /// 仅删除指定作用域，损坏 JSON 也可清理；保留其他账号与服务端消息。
  @override
  Future<void> clear(AccountScope scope) => _write((txn) async {
    await txn.rawDelete('DELETE FROM im_cache WHERE scope=?', [scope.key]);
  });

  /// 关闭本实例独占连接，保留磁盘文件；重复调用复用关闭结果。
  Future<void> close() => _closing ??= _gate.lock.synchronized(() async {
    try {
      await database.close();
    } finally {
      _release(_path, _gate);
    }
  });
}
