library;

import 'src/ports/client_cache.dart';
import 'src/adapters/cache/memory_cache.dart';

export 'src/common/types.dart';
export 'src/common/errors.dart';
export 'src/config/protocol.dart';
export 'src/config/retry_policy.dart';
export 'src/domain/account_scope.dart';
export 'src/domain/cache.dart';
export 'src/domain/message.dart';
export 'src/ports/http_transport.dart';
export 'src/ports/client_cache.dart';
export 'src/ports/remote_api.dart';
export 'src/ports/socket_connector.dart';
export 'src/ports/wire_codec.dart';
export 'src/api/http_api.dart';
export 'src/api/client.dart';
export 'src/service/sync_client.dart';
export 'src/adapters/cache/memory_cache.dart';
export 'src/adapters/codec/json_codec.dart';
export 'src/adapters/transport/package_http_transport.dart';
export 'src/adapters/transport/web_socket_connector.dart';

/// 旧版名称的源码别名；新接入请使用 ClientCache。
typedef LocalStore = ClientCache;

/// 旧版名称的源码别名；新接入请使用 MemoryCache。
typedef VolatileStore = MemoryCache;
