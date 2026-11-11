package polyglot.im

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import polyglot.im.transport.*
import polyglot.im.ports.{RemoteApi, WireCodec}
import polyglot.im.infrastructure.codec.JsonWireCodec

class PortsSuite extends CatsEffectSuite {
  test("HTTP API 支持替换编解码器，scoped 仍保留注入实现") {
    val codec = new WireCodec {
      def contentType: String         = "application/x-test-frame"
      def encode(value: Json): String = "frame:" + JsonWireCodec.encode(value)
      def decode(value: String): Either[Throwable, Json] =
        if (value.startsWith("frame:")) JsonWireCodec.decode(value.drop(6))
        else Left(new IllegalArgumentException("invalid frame"))
    }
    val transport = new HttpTransport[IO] {
      def execute(request: HttpRequest): IO[HttpResponse] = IO.pure(
        HttpResponse(200, codec.encode(Json.obj("code" -> Json.fromInt(200), "data" -> Json.obj("ok" -> Json.True))))
      )
    }
    val api: RemoteApi[IO] = new ApiClient[IO](transport, codec = codec)
    api.scoped(AccountScope("https://im.example", "app", "alice")).request("GET", "/state").map { result =>
      assertEquals(result.hcursor.get[Boolean]("ok"), Right(true))
    }
  }
}
