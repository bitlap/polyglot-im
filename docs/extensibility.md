# 模块入口与资源管理

## 分层与扩展入口

| 模块 | 入口 | 具体实现 |
| --- | --- | --- |
| Scala Server | Main.resource / bootstrap.ServerComponents、ports.UserAuthenticator | PostgreSQL、JWT / 不透明 Bearer 内省、APNs |
| Scala Client | api.ApiClient / api.ImClient | JDK HTTP/WS、SqliteStore / AtomicFileStore / PostgresStore、JDBC Outbox |
| Dart Client | HttpApi / ImClient | package:http、web_socket_channel、独立入口 SqliteStore（FFI）/ PostgresStore |
| Flutter Client | IosPushBinding、ImInbox、ImConversations、ImDirectChat | SQLite、MethodChannel |
| uni-app Client | HttpApi / ImClient / SyncClient / NotificationSender | uni.request、SocketTask、uni 持久化 |
| Python Client | ApiClient / ImClient | HTTPX、websockets、SQLite / PostgreSQL 缓存、PostgreSQL Outbox |

HTTP 路由位于 api.http，消息处理位于 service，数据和校验位于 domain，接口位于 ports，配置位于 config，具体驱动位于 infrastructure（Scala）或 adapters（Dart/Flutter/Python）。Server 的 bootstrap 负责启动与释放；Flutter 的 presentation 包含消息页组件。

## 连接与关闭

- Scala 的 HTTP、WebSocket、数据库和 Worker 通过 Resource 获取和释放。
- Python 的 open_httpx_transport、open_postgres_store 和 open_postgres_outbox 拥有所创建的连接池，退出上下文时关闭；直接构造的适配器借用宿主连接池。
- Scala/Python 的 SqliteStore 每次操作独立打开并关闭本地连接，通过 LocalStore 注入；读取不占写事务，写入原子提交，首次 WAL 初始化锁竞争有限重试。
- Dart 的 SQLite 由 sqlite.dart 独立入口提供，后台 isolate 执行 SQL；宿主显式 open/close，每次打开独占连接，同一 Dart isolate 内协调同文件写事务。
- Python/Scala/Dart 的 PostgresStore 均可借用宿主池（Scala 为 DataSource），核心存储端口不绑定驱动；Python/Dart 另有显式自有池工厂。初始化、写事务和清理使用 PostgreSQL 事务锁，资源约定见[客户端存储](client/storage.md)。
- Flutter 的 SqliteStore 由宿主打开和关闭；账号或环境切换时释放旧客户端，再使用新的缓存命名空间。
- 消息与游标落盘成功后才允许 ack。服务端发送在同一事务写入消息、事件和 Outbox。

注入自有资源的可运行示例：[Scala](../examples/scala-client/src/main/scala/polyglot/im/examples/ClientWiring.scala)、[Dart](../examples/dart-client/pluggable.dart)、[Python](../examples/python-client/pluggable.py)、[Server 鉴权](../examples/scala-server/src/main/scala/polyglot/im/server/ServerWiring.scala)。

## 模块与包名

| 模块 | 发布名 / 导入名 |
| --- | --- |
| scala-client | org.bitlap:polyglot-im-scala-client_3 / polyglot.im |
| scala-server | polyglot-im-scala-server（构建模块，不发布） |
| dart-client | polyglot_im |
| flutter-client | polyglot_im_flutter |
| python-client | polyglot-im / polyglot_im |
| uniapp-client | @bitlap/polyglot-im |

Flutter 应用只需依赖 Flutter Client；该包导出 Dart Client 的公共 API。纯 Dart 工程直接使用 Dart Client。所有模块版本读取根目录 VERSION。

uni-app 的通知/单聊完整 SDK 及 HTTP/Socket/存储/codec/重试替换入口见[客户端说明](../uniapp-client/README.md)。
