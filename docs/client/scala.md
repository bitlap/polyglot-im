# Scala Client 集成

Scala 3.6.3、JDK 17+，Cats / Cats Effect。scala-client 目录内使用独立 sbt 构建，服务端通过跨构建源码依赖引用 Client，公开 Maven 坐标尚未发布。Client 核心使用 F[_]，FS2 流和 Resource 管理网络生命周期。

## 组合生产者

```scala
import java.net.URI
import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import polyglot.im.*
import polyglot.im.transport.*

object Publish extends IOApp.Simple {
  val run: IO[Unit] = JdkTransport.resource[IO](URI.create(sys.env("IM_BASE_URL"))).use {
    case (transport, _) =>
      val tokens = new TokenProvider[IO] {
        def token: IO[String] = IO(sys.env("IM_SERVICE_TOKEN"))
      }
      val api = new ApiClient[IO](new AuthenticatedTransport[IO](transport, tokens))
      new NotificationSender[IO](api).send(
        "recipient-id", "task:stable-business-event-id:completed", "task.completed",
        Json.obj("task_id" -> Json.fromString("task-id"), "text" -> Json.fromString("创作完成"))
      ).void
  }
}
```

`clientMessageId` 来自业务事件，重复发送返回原消息；同键不同接收者或 payload 返回 409。实际生产端在业务事务写本地 Outbox，Worker 调用 Client，见[业务接入](../integration.md)。不在 HTTP 请求结束后启动无人管理的全局 Fiber。

## 用户客户端

在同一个网络 Resource 内创建用户认证的 ApiClient，使用：

```scala
import java.nio.file.Path
import cats.effect.Resource

val store = new SqliteStore[IO](Path.of("/app/data/im.sqlite"))
val scope = AccountScope(baseUrl, appId, userId)
val resource = Resource.make(
  ImClient.create[IO](userApi, scope, deviceId, store, sockets)
)(_.close())
resource.use { client =>
  client.initialize *> client.watch.evalMap(render).compile.drain
}
```

`sockets` 是 `JdkTransport.resource` 返回的第二个值；userApi 必须使用用户凭证。`state`、`sync`、`history`、`getMessage`、`markRead`、`setPush` 都返回 F；`watch` 是可取消流。宿主需要后台消费时用 Resource 管理 `.compile.drain.background`，退出时先取消流再关闭客户端。`close` 不会接管宿主启动的所有消费 Fiber。

`SqliteStore[F]` 是基于 [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc) 的本地适配器，驱动依赖随 Scala Client 声明，支持 JDK 17+。传入持久文件路径，父目录在首次操作时创建；空路径、`:memory:` 和 `file:` URI 被拒绝，请传普通文件路径。每次操作在 `Async.blocking` 中独立打开/关闭连接，无长期连接池或必须调用的 store.close；客户端仍由宿主管理生命周期。

采用 WAL、synchronous=FULL 与 15 秒 busy_timeout；首次并发切换 WAL 的 BUSY 按剩余预算有限重试，只重试事务开始前的幂等配置，不重放业务事务。提交先 BEGIN IMMEDIATE，再读取并合并当前 scope 的通知、单聊与水位，统一提交；失败回滚。普通读取不占写事务，clear 只删除当前 scope，即使其 JSON 损坏也可清理恢复。账号/环境/应用隔离，ID 与大序号仍保留为 JSON 字符串。

与当前 Flutter/Python SQLite 适配器相同，按 scope 保存 JSON 缓存；本次不新增逐消息 SQL 索引、数据库级历史分页或跨语言共享文件格式。重启时复用相同路径与 scope。文件目录权限和磁盘加密由宿主配置，缓存不保存 Bearer。

`AtomicFileStore[F]` 仍作为可选轻量适配器保留，使用文件锁、fsync 和原子替换。宿主也可实现 `LocalStore[F]`；同一命名空间的 commit 必须串行且原子。

## PostgreSQL 客户端缓存

`PostgresStore[F]` 实现同一 LocalStore，借用宿主 DataSource，不创建或关闭连接池。驱动随 Scala Client 声明；宿主通过 Resource 管理池、TLS、池大小和连接/查询超时：

```scala
val store = new PostgresStore[IO](dataSource, namespace = "desktop-installation")
store.initialize *> Resource.make(
  ImClient.create[IO](userApi, scope, deviceId, store, sockets)
)(_.close()).use(client => client.initialize *> client.sync.void)
```

initialize 显式建表；后续 load/commit/clear 每次借用并归还连接，JDBC 在 Async.blocking 中执行。消息、会话与游标在 READ COMMITTED 事务和作用域锁内原子提交；失败回滚，lockTimeoutMillis 默认 15000。namespace 必须显式传入，schema 默认 snap_flow，表为 im_client_cache。缓存与 JdbcOutbox 分开；事务、隔离和性能边界见[客户端存储](storage.md)。

`HttpTransport[F]`、`TokenProvider[F]`、`SocketConnector[F]`、`LocalStore[F]` 均可替换；`ApiClient` 支持注入认证刷新效果。测试使用真实 PostgreSQL 和 http4s 服务验证完整 Client 网络路径。

业务 PostgreSQL Outbox 可使用 `JdbcOutbox[F]` 与 `NotificationIntent`：同一事务调用业务 SQL 和 enqueue，后台 drainOnce 消费，不提交宿主事务。完整事务边界见[业务接入](../integration.md)。

## 用户文字单聊

会话创建、文字与 Unicode 表情发送、历史和已读回执的完整例子见[单聊集成](../direct-chat.md)。图片与 COS 暂不接入。
