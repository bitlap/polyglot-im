import 'dart:convert';
import 'dart:math';
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
import 'package:sqflite/sqflite.dart';

/// 消息与游标在同一 SQLite 事务提交；数据库生命周期由宿主显式管理。
class SqliteStore implements LocalStore {
  SqliteStore(this.database);
  final Database database;

  static Future<SqliteStore> openDefault() async =>
      open('${await getDatabasesPath()}/polyglot_im.db');

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

  @override
  Future<JsonMap> load(AccountScope scope) => _load(database, scope);

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

  @override
  Future<void> clear(AccountScope scope) async {
    await database.delete(
      'im_cache',
      where: 'scope = ?',
      whereArgs: [scope.key],
    );
  }

  Future<void> close() => database.close();
}
