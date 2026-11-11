package polyglot.im

import java.nio.file.{Files, Path}
import cats.effect.{IO, Ref}
import io.circe.parser.parse
import munit.CatsEffectSuite
import polyglot.im.transport.*

class HttpEnvelopeSuite extends CatsEffectSuite {
  private def fixture(name: String): String =
    Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", name + ".json"))
  test("共享 HTTP 信封、HTTP 201 与稳定业务错误码") {
    for {
      response <- Ref.of[IO, HttpResponse](HttpResponse(201, fixture("http-success")))
      transport = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] =
          IO(assertEquals(request.path, "/api/v1/im/notifications")) *> response.get
      }
      api = new ApiClient[IO](transport)
      result <- api.request("POST", "/notifications")
      _ = assertEquals(result, parse(fixture("http-success")).toOption.get.hcursor.downField("data").focus.get)
      _     <- response.set(HttpResponse(409, fixture("http-error")))
      error <- api.request("POST", "/notifications").attempt
      _ = assertEquals(error.swap.toOption.get.asInstanceOf[ImError].code, "IDEMPOTENCY_CONFLICT")
      _         <- response.set(HttpResponse(200, "{\"code\":200,\"message\":\"success\",\"data\":null}"))
      malformed <- api.request("POST", "/notifications").attempt
      _ = assert(malformed.isLeft)
    } yield ()
  }
}
