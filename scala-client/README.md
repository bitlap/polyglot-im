# Polyglot IM Scala Client

Scala 3 / Cats Effect 通知 Client，提供通知发送、HTTP/WebSocket 同步、账户隔离的 SQLite / PostgreSQL 消息缓存与可信后端 PostgreSQL 业务 Outbox。网络、凭证与本地存储通过端口注入，使用 `F[_]`、FS2 和 `Resource` 管理异步操作及资源生命周期。

## 使用示例

JDK 17+、Scala 3.6.3，本目录提供独立的 sbt 构建，包命名空间为 `polyglot.im`。

发布组织为 `org.bitlap`，本地依赖写作 `"org.bitlap" %% "polyglot-im-scala-client" % "0.1.1"`。在仓库根执行 `just publish-local-scala` 发布到 Ivy local，完整坐标与接入见[构建文档](../docs/building.md#本地-scala-依赖)。

```scala
import java.net.URI
import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import polyglot.im.*
import polyglot.im.transport.*

object Publish extends IOApp.Simple {
  val run: IO[Unit] =
    JdkTransport.resource[IO](URI.create(sys.env("IM_BASE_URL"))).use {
      case (transport, _) =>
        val tokens = new TokenProvider[IO] {
          def token: IO[String] = IO(sys.env("IM_SERVICE_TOKEN"))
        }
        val api = new ApiClient[IO](new AuthenticatedTransport[IO](transport, tokens))
        new NotificationSender[IO](api).send(
          "user-123", "task:456:completed", "task.completed",
          Json.obj("task_id" -> Json.fromString("456"), "text" -> Json.fromString("创作完成"))
        ).void
    }
}
```

发送重试复用同一业务事件 ID。可靠业务通知使用 `JdbcOutbox[F]`，把通知意图与业务状态放在同一个 PostgreSQL 事务提交；服务凭证仅用于可信后端。

## 本地 SQLite 存储

用户客户端通过 `LocalStore[F]` 注入存储，随包提供 `SqliteStore[F]`：

```scala
import java.nio.file.Path
import cats.effect.IO
import polyglot.im.SqliteStore

val store = new SqliteStore[IO](Path.of("/app/data/im.sqlite"))
// 将 store 传入 ImClient.create；复用相同路径和 scope 可重启恢复。
```

每次操作在阻塞效果中独立打开/关闭连接，消息、会话与游标同事务提交；WAL 普通读取不占写事务，并发写入串行合并、失败回滚。拒绝空路径与内存数据库，父目录自动创建。原子文件适配器仍可选，宿主可实现其他 LocalStore；完整行为与格式边界见[Scala 集成](../docs/client/scala.md)。

## PostgreSQL 消息存储

可信宿主可将 `new PostgresStore[IO](dataSource, namespace = "desktop-installation")` 作为同一 LocalStore 注入客户端，先显式执行 store.initialize；池生命周期由宿主 Resource 管理。消息、会话与游标在作用域锁和同一事务中提交，失败回滚；它与 JdbcOutbox 分开。完整接入见[Scala 集成](../docs/client/scala.md)，各端适配与边界见[客户端存储](../docs/client/storage.md)。

## 测试与构建

在本目录执行：

```sh
sbt test
sbt package
```

[可运行示例](../examples/scala-client/README.md) · [完整集成](../docs/client/scala.md) · [业务事务 Outbox](../docs/integration.md) · [返回 Polyglot IM 项目首页](../README.md)

## 用户单聊

支持文字与 Unicode 表情、唯一双人会话、可靠同步、历史分页和已读回执。完整接口与使用例子见[单聊集成](../docs/direct-chat.md)。图片与 COS 接入暂不支持。

HTTP 使用 `code/message/data` 通用信封，Client 自动解包；服务端公共前缀由 http4s Router 统一挂载。完整约定见[HTTP 协议](../docs/protocol.md)。

## 分层与替换适配器

api 提供装配入口，service 编排同步与发送，domain 维护模型与缓存归并；ports 定义 RemoteApi、HttpTransport、SocketConnector、LocalStore、WireCodec 和 NotificationPublisher。具体 JDK、SQLite/PostgreSQL/原子文件缓存与 PostgreSQL Outbox 位于 infrastructure，重试参数位于 config。

默认用法保持兼容。自定义网络、帧或缓存时，将同一 WireCodec 传给网络适配器、ApiClient 和 ImClient.create；资源仍通过 Resource 显式释放。见[装配示例](../examples/scala-client/src/main/scala/polyglot/im/examples/ClientWiring.scala)及[分层契约](../docs/extensibility.md#分层与扩展入口)。
