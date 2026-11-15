library;

import 'src/adapters/cache/sqlite_cache.dart';

export 'package:polyglot_im/polyglot_im.dart';
export 'src/adapters/cache/sqlite_cache.dart';
export 'src/adapters/push/method_channel_push.dart';
export 'src/ports/ios_push_platform.dart';
export 'src/service/push_binding.dart';
export 'src/service/lifecycle.dart';
export 'src/api/ios_push_binding.dart';
export 'src/presentation/inbox.dart';
export 'src/presentation/direct_chat.dart';
export 'src/presentation/conversations.dart';

/// 旧版名称的源码别名；新接入请使用 SqliteCache。
typedef SqliteStore = SqliteCache;
