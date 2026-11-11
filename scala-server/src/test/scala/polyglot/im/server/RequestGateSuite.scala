package polyglot.im.server

import polyglot.im.server.api.http.RequestGate

import cats.data.Kleisli
import cats.effect.{Deferred, IO}
import munit.CatsEffectSuite
import org.http4s.{HttpApp, Request, Response, Status, Uri}

class RequestGateSuite extends CatsEffectSuite {
  test("满载立即 503，存活探针可用；取消请求释放配额") {
    RequestGate.resource[IO](1).use { gate =>
      for {
        entered <- Deferred[IO, Unit]
        pending: HttpApp[IO] = Kleisli(_ => entered.complete(()) *> IO.never[Response[IO]])
        first <- gate(pending)(Request[IO]()).start
        _     <- entered.get
        normal: HttpApp[IO] = Kleisli(_ => IO.pure(Response[IO](Status.Ok)))
        blocked <- gate(normal)(Request[IO]())
        _ = assertEquals(blocked.status, Status.ServiceUnavailable)
        body <- blocked.as[String]
        error = io.circe.parser.parse(body).toOption.get
        _     = assertEquals(error.hcursor.get[Int]("code").toOption, Some(503))
        _     = assertEquals(error.hcursor.get[String]("error_code").toOption, Some("OVERLOADED"))
        _     = assert(error.hcursor.downField("data").focus.get.isNull)
        live <- gate(normal)(Request[IO](uri = Uri.unsafeFromString("/health/live")))
        _ = assertEquals(live.status, Status.Ok)
        _        <- first.cancel
        accepted <- gate(normal)(Request[IO]())
        _ = assertEquals(accepted.status, Status.Ok)
      } yield ()
    }
  }
}
