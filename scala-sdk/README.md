# Polyglot IM Scala SDK

Scala 3 / Cats Effect 通知 SDK，提供通知发送、HTTP/WebSocket 同步、账户隔离缓存与 PostgreSQL 业务 Outbox。网络、凭证与本地存储通过端口注入，使用 `F[_]`、FS2 和 `Resource` 管理异步操作及资源生命周期。

## 使用示例

JDK 17+、Scala 3.6.3，本目录提供独立的 sbt 构建，包命名空间为 `polyglot.im`。

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

## 测试与构建

在本目录执行：

```sh
sbt test
sbt package
```

[可运行示例](../examples/scala-sdk/README.md) · [完整集成](../docs/sdk/scala.md) · [业务事务 Outbox](../docs/integration.md) · [返回 Polyglot IM 项目首页](../README.md)
