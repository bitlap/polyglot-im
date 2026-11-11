package polyglot.im.server

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
      assertEquals(parse(json).toOption.get.hcursor.get[String]("stage").toOption.get, "running")
      assertEquals(ready.status, Status.ServiceUnavailable)
      assertEquals(missing.status, Status.NotFound)
    }
  }

  test("替换就绪检查并组合业务路由，检查异常时保持未就绪") {
    val extra = HttpRoutes.of[IO] { case GET -> Root / "extension" => Ok("plugin") }
    for {
      extension <- Server.app[IO](readiness(IO.pure(true)), extra)(
        Request[IO](uri = Uri.unsafeFromString("/extension"))
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
}
