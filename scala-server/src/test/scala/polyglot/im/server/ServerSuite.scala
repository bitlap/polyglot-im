package polyglot.im.server

import polyglot.im.server.api.http.Api
import polyglot.im.server.api.http.Server
import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.ports.Authenticator
import polyglot.im.server.ports.Readiness
import polyglot.im.server.security.CursorCodec

import cats.effect.IO
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, Method, Request, Status, Uri}
import org.http4s.dsl.io.*

class ServerSuite extends CatsEffectSuite {
  private def readiness(value: IO[Boolean]): Readiness[IO] = new Readiness[IO] { def check = value }

  test("健康检查保持原有 HTTP 状态与 JSON 契约") {
    val app = Server.app[IO](readiness(IO.pure(false)), HttpRoutes.empty[IO])
    for {
      live    <- app(Request[IO](Method.GET, Uri.unsafeFromString("/health/live")))
      json    <- live.as[String]
      ready   <- app(Request[IO](Method.GET, Uri.unsafeFromString("/health/ready")))
      missing <- app(Request[IO](Method.GET, Uri.unsafeFromString("/api/v1/im/conversations")))
    } yield {
      assertEquals(live.status, Status.Ok)
      assertEquals(parse(json).toOption.get.hcursor.downField("data").get[String]("stage").toOption.get, "running")
      assertEquals(ready.status, Status.ServiceUnavailable)
      assertEquals(missing.status, Status.NotFound)
    }
  }

  test("替换就绪检查并组合业务路由，检查异常时保持未就绪") {
    val extra = HttpRoutes.of[IO] { case GET -> Root / "extension" => Ok("plugin") }
    for {
      extension <- Server.app[IO](readiness(IO.pure(true)), extra)(
        Request[IO](uri = Uri.unsafeFromString(Server.apiPrefix + "/extension"))
      )
      text  <- extension.as[String]
      ready <- Server.app[IO](readiness(IO.pure(true)), extra)(Request[IO](uri = Uri.unsafeFromString("/health/ready")))
      failed <- Server.app[IO](readiness(IO.raiseError(new RuntimeException("dependency failed"))), extra)(
        Request[IO](uri = Uri.unsafeFromString("/health/ready"))
      )
    } yield {
      assertEquals(text, "plugin")
      assertEquals(ready.status, Status.Ok)
      assertEquals(failed.status, Status.ServiceUnavailable)
    }
  }
  test("http4s 统一挂载前缀，未挂载/重复前缀不匹配；错误和探针采用统一信封") {
    for {
      ws <- org.http4s.server.websocket.WebSocketBuilder2[IO]
      auth = new Authenticator[IO] {
        def authenticate(token: String): IO[Identity] = IO.raiseError(ApiError(401, "UNAUTHORIZED", "login required"))
      }
      api = new Api[IO](null, auth, new CursorCodec("c" * 32), null)
      app = Server.app[IO](readiness(IO.pure(true)), api.routes(ws))
      response <- app(Request[IO](uri = Uri.unsafeFromString(Server.apiPrefix + "/state")))
      text     <- response.as[String]
      body = parse(text).toOption.get
      _    = assertEquals(response.status.code, 401)
      _    = assertEquals(body.hcursor.get[Int]("code").toOption, Some(401))
      _    = assertEquals(body.hcursor.get[String]("error_code").toOption, Some("UNAUTHORIZED"))
      _    = assert(body.hcursor.downField("data").focus.get.isNull)
      missing   <- app(Request[IO](uri = Uri.unsafeFromString("/state")))
      duplicate <- app(Request[IO](uri = Uri.unsafeFromString(Server.apiPrefix + Server.apiPrefix + "/state")))
      _ = assertEquals(List(missing.status.code, duplicate.status.code), List(404, 404))
      live     <- app(Request[IO](uri = Uri.unsafeFromString("/health/live")))
      liveText <- live.as[String]
      data = parse(liveText).toOption.get
      _    = assertEquals(data.hcursor.get[Int]("code").toOption, Some(200))
      _    = assertEquals(data.hcursor.downField("data").get[String]("status").toOption, Some("alive"))
    } yield ()
  }
}
