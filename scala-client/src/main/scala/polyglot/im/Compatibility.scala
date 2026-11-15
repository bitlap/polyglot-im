package polyglot.im

/** 0.1.x 源码兼容导出。 */
type AccountScope = polyglot.im.domain.AccountScope
lazy val AccountScope = polyglot.im.domain.AccountScope
type ApiClient[F[_]] = polyglot.im.api.ApiClient[F]
lazy val ApiClient = polyglot.im.api.ApiClient
type AtomicFileCache[F[_]] = polyglot.im.infrastructure.cache.AtomicFileCache[F]
type SqliteCache[F[_]]     = polyglot.im.infrastructure.cache.SqliteCache[F]
type PostgresCache[F[_]]   = polyglot.im.infrastructure.cache.PostgresCache[F]
type ImClient[F[_]]        = polyglot.im.service.ImClient[F]
lazy val ImClient = polyglot.im.api.ImClient
type ImError = polyglot.im.domain.ImError
lazy val ImError = polyglot.im.domain.ImError
type PostgresOutbox[F[_]] = polyglot.im.infrastructure.store.PostgresOutbox[F]
lazy val JsonWireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
lazy val LocalCache    = polyglot.im.domain.LocalCache
type ClientCache[F[_]] = polyglot.im.ports.ClientCache[F]
type Message           = polyglot.im.domain.Message
lazy val Message = polyglot.im.domain.Message
type NotificationIntent = polyglot.im.domain.NotificationIntent
lazy val NotificationIntent = polyglot.im.domain.NotificationIntent
type NotificationSender[F[_]] = polyglot.im.service.NotificationSender[F]
lazy val ProtocolConfig = polyglot.im.config.ProtocolConfig
type RemoteApi[F[_]] = polyglot.im.ports.RemoteApi[F]
type RetryPolicy     = polyglot.im.config.RetryPolicy
lazy val RetryPolicy = polyglot.im.config.RetryPolicy
type SocketConnector[F[_]] = polyglot.im.ports.SocketConnector[F]
type SocketSession[F[_]]   = polyglot.im.ports.SocketSession[F]
type WireCodec             = polyglot.im.ports.WireCodec

type NotificationPublisher[F[_]] = polyglot.im.ports.NotificationPublisher[F]

/** 旧版缓存名称的源码别名；新接入使用 ClientCache 与对应 Cache 实现。 */
type LocalStore[F[_]]      = ClientCache[F]
type SqliteStore[F[_]]     = SqliteCache[F]
type PostgresStore[F[_]]   = PostgresCache[F]
type AtomicFileStore[F[_]] = AtomicFileCache[F]
type JdbcOutbox[F[_]]      = PostgresOutbox[F]
