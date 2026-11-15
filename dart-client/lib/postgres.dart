/// 可信 Dart VM 宿主显式选用的 PostgreSQL 客户端缓存；Flutter 主入口不导出。
library;

import 'src/adapters/cache/postgres_cache.dart';

export 'src/adapters/cache/postgres_cache.dart';

/// 旧版名称的源码别名；新接入请使用 PostgresCache。
typedef PostgresStore = PostgresCache;
