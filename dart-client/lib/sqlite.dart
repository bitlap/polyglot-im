/// Dart VM 本地 SQLite 适配；Flutter 平台适配器仍由 Flutter Client 导出。
library;

import 'src/adapters/cache/sqlite_cache.dart';

export 'src/adapters/cache/sqlite_cache.dart';

/// 旧版名称的源码别名；新接入请使用 SqliteCache。
typedef SqliteStore = SqliteCache;
