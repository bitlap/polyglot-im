package polyglot.im

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import polyglot.im.transport.*

class ClientBehaviorSuite extends CatsEffectSuite {
  test("401 只刷新一次；刷新后仍失败则返回认证错误") {
    for {
      calls <- Ref.of[IO, Int](0); refreshes <- Ref.of[IO, Int](0)
      transport = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] =
          calls.update(_ + 1).as(HttpResponse(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"expired\"}"))
      }
      result <- new ApiClient[IO](transport, Some(refreshes.update(_ + 1))).request("GET", "/state").attempt
      count  <- calls.get; refreshed <- refreshes.get
      _ = assertEquals(count, 2)
      _ = assertEquals(refreshed, 1)
      _ = assertEquals(result.swap.toOption.get.asInstanceOf[ImError].status, 401)
    } yield ()
  }
  test("403 不刷新、不重试；空幂等键不产生网络调用") {
    for {
      calls <- Ref.of[IO, Int](0); refreshes <- Ref.of[IO, Int](0)
      transport = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] = calls.update(_ + 1).as(HttpResponse(403, "{}"))
      }
      api = new ApiClient[IO](transport, Some(refreshes.update(_ + 1)))
      result  <- api.request("GET", "/state").attempt
      invalid <- new NotificationSender[IO](api).send("alice", "", "system.notice", Json.obj()).attempt
      count   <- calls.get; refreshed <- refreshes.get
      _ = assert(result.isLeft && invalid.isLeft)
      _ = assertEquals(count, 1)
      _ = assertEquals(refreshed, 0)
    } yield ()
  }
  test("凭证请求携带 URL 编码的账户断言，不能跨账户复用 scope") {
    for {
      observed <- Ref.of[IO, Map[String, String]](Map.empty)
      transport = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] = observed.set(request.headers).as(HttpResponse(200, "{}"))
      }
      _ <- new ApiClient[IO](transport)
        .scoped(AccountScope("https://im.example", "应用", "用户 1"))
        .request("GET", "/state")
      headers <- observed.get
      _ = assertEquals(
        java.net.URLDecoder.decode(headers("X-IM-User-ID"), java.nio.charset.StandardCharsets.UTF_8),
        "用户 1"
      )
      _ = assertNotEquals(AccountScope("a", "app", "alice").key, AccountScope("b", "app", "alice").key)
    } yield ()
  }
  test("缓存提交失败时不公开新游标；未知事件仍可推进同步进度") {
    val scope = AccountScope("https://im.example", "app", "alice")
    val page = Json.obj(
      "cursor"         -> Json.fromString("new-cursor"),
      "event_position" -> Json.fromString("3"),
      "events"         -> Json.arr(Json.obj("type" -> Json.fromString("future.event")))
    )
    val transport = new HttpTransport[IO] {
      def execute(request: HttpRequest): IO[HttpResponse] = IO.pure(
        HttpResponse(
          200,
          if (request.path.endsWith("/session")) "{\"app_id\":\"app\",\"user_id\":\"alice\"}" else page.noSpaces
        )
      )
    }
    val store = new LocalStore[IO] {
      def load(s: AccountScope): IO[Json] = IO.pure(LocalCache.empty)
      def commit(s: AccountScope, p: Json, reset: Boolean): IO[Json] =
        IO.raiseError(new java.io.IOException("disk full"))
      def clear(s: AccountScope): IO[Unit] = IO.unit
    }
    val sockets = new SocketConnector[IO] {
      def connect(uri: java.net.URI): Resource[IO, SocketSession[IO]] =
        Resource.eval(IO.raiseError(new IllegalStateException("not used")))
    }
    for {
      client <- ImClient.create(new ApiClient[IO](transport), scope, "ios", store, sockets)
      result <- client.initialize.attempt
      state  <- client.state
      _ = assert(result.isLeft)
      _ = assertEquals(state, LocalCache.empty)
      _ = assertEquals(LocalCache.field(LocalCache.applyPage(LocalCache.empty, page), "cursor"), "new-cursor")
    } yield ()
  }
}
