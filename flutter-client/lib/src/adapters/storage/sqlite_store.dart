import 'dart:convert';
import 'dart:math';
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';
import 'package:sqflite/sqflite.dart';

/// 消息与游标在同一 SQLite 事务提交；数据库生命周期由宿主显式管理。
class SqliteStore implements LocalStore {
  /// 借用已打开的 SQLite 数据库；宿主负责在不再使用时调用 close。
  SqliteStore(this.database);
  final Database database;

  /// 在应用默认持久目录打开本地 SQLite 缓存，由调用方负责关闭。
  static Future<SqliteStore> openDefault() async =>
      open('${await getDatabasesPath()}/polyglot_im.db');

  /// 读取或事务性创建安装级随机设备身份，不使用硬件标识。
  Future<String> installationId() => database.transaction((txn) async {
    await txn.execute(
      'CREATE TABLE IF NOT EXISTS im_installation(id TEXT PRIMARY KEY)',
    );
    final existing = await txn.query('im_installation');
    if (existing.isNotEmpty) return existing.single['id'] as String;
    final random = Random.secure();
    final id = List.generate(
      32,
      (_) => random.nextInt(16).toRadixString(16),
    ).join();
    await txn.insert('im_installation', {'id': id});
    return id;
  });

  /// 打开指定文件的 SQLite 缓存并初始化本地表，返回可关闭的存储实例。
  static Future<SqliteStore> open(String path) async {
    final db = await openDatabase(
      path,
      version: 1,
      onCreate: (db, _) async {
        await db.execute(
          'CREATE TABLE im_cache(scope TEXT PRIMARY KEY, data TEXT NOT NULL)',
        );
      },
    );
    return SqliteStore(db);
  }

  Future<JsonMap> _load(DatabaseExecutor db, AccountScope scope) async {
    final rows = await db.query(
      'im_cache',
      where: 'scope = ?',
      whereArgs: [scope.key],
    );
    return rows.isEmpty
        ? emptyCache()
        : jsonDecode(rows.single['data'] as String) as JsonMap;
  }

  /// 读取指定账号作用域的缓存；尚无记录时返回空状态。
  @override
  Future<JsonMap> load(AccountScope scope) => _load(database, scope);

  /// 原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) => database.transaction((txn) async {
    final value = applyPage(await _load(txn, scope), page, reset: reset);
    await txn.insert('im_cache', {
      'scope': scope.key,
      'data': jsonEncode(value),
    }, conflictAlgorithm: ConflictAlgorithm.replace);
    return value;
  });

  /// 只清除指定账号作用域的本地缓存，不删除服务器消息。
  @override
  Future<void> clear(AccountScope scope) async {
    await database.delete(
      'im_cache',
      where: 'scope = ?',
      whereArgs: [scope.key],
    );
  }

  /// 关闭当前 SQLite 数据库连接，保留已提交的磁盘缓存。
  Future<void> close() => database.close();
}
