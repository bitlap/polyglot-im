# Scala SDK 集成

Scala 3.6.3、JDK 17+，Cats / Cats Effect。仓库内依赖 `scalaSdk`，公开 Maven 坐标尚未发布。SDK 核心使用 F[_]，FS2 流和 Resource 管理网络生命周期。

## 组合生产者

```scala
import java.net.URI
import cats.effect.{IO, IOApp}
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

`clientMessageId` 来自业务事件，重复发送返回原消息；同键不同接收者或 payload 返回 409。实际生产端在业务事务写本地 Outbox，Worker 调用 SDK，见[业务接入](../integration.md)。不在 HTTP 请求结束后启动无人管理的全局 Fiber。

## 用户客户端

在同一个网络 Resource 内创建用户认证的 ApiClient，使用：

```scala
import java.nio.file.Path
import cats.effect.Resource

val store = new AtomicFileStore[IO](Path.of("/app/data/im-cache"))
val scope = AccountScope(baseUrl, appId, userId)
val resource = Resource.make(
  ImClient.create[IO](userApi, scope, deviceId, store, sockets)
)(_.close())
resource.use { client =>
  client.initialize *> client.watch.evalMap(render).compile.drain
}
```

`sockets` 是 `JdkTransport.resource` 返回的第二个值；userApi 必须使用用户凭证。`state`、`sync`、`history`、`getMessage`、`markRead`、`setPush` 都返回 F；`watch` 是可取消流。宿主需要后台消费时用 Resource 管理 `.compile.drain.background`，退出时先取消流再关闭客户端。`close` 不会接管宿主启动的所有消费 Fiber。

AtomicFileStore 使用文件锁、fsync 和原子替换保存消息与游标，适合 JVM 本地磁盘。集群共享缓存或数据库缓存请实现 `LocalStore[F]`，同一命名空间的 commit 必须串行且原子。消息并不保存令牌；目录权限和磁盘加密由宿主配置。

`HttpTransport[F]`、`TokenProvider[F]`、`SocketConnector[F]`、`LocalStore[F]` 均可替换；`ApiClient` 支持注入认证刷新效果。测试使用真实 PostgreSQL 和 http4s 服务验证完整 SDK 网络路径。

业务 PostgreSQL Outbox 可使用 `JdbcOutbox[F]` 与 `NotificationIntent`：同一事务调用业务 SQL 和 enqueue，后台 drainOnce 消费，不提交宿主事务。完整事务边界见[业务接入](../integration.md)。
