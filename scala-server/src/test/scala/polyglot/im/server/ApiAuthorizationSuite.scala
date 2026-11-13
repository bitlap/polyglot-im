package polyglot.im.server

import polyglot.im.server.api.http.Api
import polyglot.im.server.api.http.Server
import polyglot.im.server.config.RateLimits
import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.ports.Authenticator
import polyglot.im.server.security.CursorCodec
import polyglot.im.server.service.RateLimiter as RateLimiters

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.{Method, Request, Uri, Header}
import org.http4s.server.websocket.WebSocketBuilder2
import org.typelevel.ci.CIString

class ApiAuthorizationSuite extends CatsEffectSuite {
  private def app(identity: Either[ApiError, Identity], rates: RateLimits = RateLimits()) = for {
    limits <- Ref.of[IO, Map[String, (Long, Int)]](Map.empty)
    ws     <- WebSocketBuilder2[IO]
    auth = new Authenticator[IO] { def authenticate(token: String): IO[Identity] = IO.fromEither(identity) }
    // 故意不注入数据库：这些拒绝必须发生在任何持久层访问之前。
    api = new Api[IO](null, auth, new CursorCodec("c" * 32), RateLimiters.inMemory(limits, () => 6000L), rates)
  } yield Server.apiRoutes(api.routes(ws)).orNotFound
  private val user = Identity("app", "alice", "user", Long.MaxValue)
  test("通知操作拒绝非法批次与服务身份，拒绝发生在持久层之前") {
    for {
      routes <- app(Right(user))
      invalid <- List(
        Json.obj(),
        Json.obj("message_ids" -> Json.arr()),
        Json.obj("message_ids" -> Json.arr(Json.fromString("a"), Json.fromString("a")))
      ).traverse { body =>
        routes(Request[IO](Method.PUT, Uri.unsafeFromString("/api/v1/im/notifications/read")).withEntity(body.noSpaces))
      }
      _ = assert(invalid.forall(_.status.code == 422))
      service   <- app(Right(user.copy(role = "service")))
      forbidden <- service(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/im/notifications/delete")))
      _ = assertEquals(forbidden.status.code, 403)
    } yield ()
  }
  test("未认证、错误角色和账户断言不匹配在数据库访问前被拒绝") {
    for {
      missing       <- app(Left(ApiError(401, "UNAUTHORIZED", "missing")))
      r1            <- missing(Request[IO](uri = Uri.unsafeFromString("/api/v1/im/state")))
      authenticated <- app(Right(user))
      r2            <- authenticated(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/im/notifications")))
      r3 <- authenticated(
        Request[IO](uri = Uri.unsafeFromString("/api/v1/im/snapshot"))
          .putHeaders(Header.Raw(CIString("X-IM-User-ID"), "bob"))
      )
      _ = assertEquals(List(r1.status.code, r2.status.code, r3.status.code), List(401, 403, 403))
    } yield ()
  }
  test("服务身份范围和限流不能由客户端 body 绕过") {
    val service = user.copy(role = "service", types = Set("system.notice"), recipients = Set("bob"))
    val body = Json
      .obj(
        "recipient_id"      -> Json.fromString("alice"),
        "client_message_id" -> Json.fromString("event"),
        "type"              -> Json.fromString("system.notice"),
        "payload"           -> Json.obj()
      )
      .noSpaces
    for {
      routes <- app(Right(service), RateLimits(600, 1))
      request = Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/im/notifications")).withEntity(body)
      rejected <- routes(request)
      limited  <- routes(request)
      _ = assertEquals(rejected.status.code, 403)
      _ = assertEquals(limited.status.code, 429)
    } yield ()
  }
  test("服务身份不能使用用户单聊路由") {
    for {
      routes  <- app(Right(user.copy(role = "service")))
      created <- routes(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/im/conversations/direct")))
      sent    <- routes(Request[IO](Method.POST, Uri.unsafeFromString("/api/v1/im/conversations/foreign/messages")))
      _ = assertEquals(List(created.status.code, sent.status.code), List(403, 403))
    } yield ()
  }
  test("非法推送字段不能当作解绑或偏好缺省，错误身份编码返回参数错误") {
    val invalid = List(
      Json.obj(),
      Json.obj("token" -> Json.fromInt(123)),
      Json.obj("token" -> Json.fromString("")),
      Json.obj("token" -> Json.Null, "muted"   -> Json.fromString("true")),
      Json.obj("token" -> Json.Null, "preview" -> Json.Null)
    )
    for {
      routes <- app(Right(user))
      responses <- invalid.traverse { data =>
        routes(
          Request[IO](Method.PUT, Uri.unsafeFromString("/api/v1/im/devices/ios/push-token"))
            .withEntity(data.noSpaces)
        )
      }
      malformed <- routes(
        Request[IO](uri = Uri.unsafeFromString("/api/v1/im/state"))
          .putHeaders(Header.Raw(CIString("X-IM-User-ID"), "%ZZ"))
      )
      _ = assert(responses.forall(_.status.code == 422))
      _ = assertEquals(malformed.status.code, 422)
    } yield ()
  }

}
