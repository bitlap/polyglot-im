package polyglot.im

import cats.effect.{IO, Ref}
import munit.CatsEffectSuite
import polyglot.im.transport.*

class TransportSuite extends CatsEffectSuite {
  test("可注入网络适配器；每次请求读取新凭证并替换大小写不敏感的旧认证头") {
    for {
      saved      <- Ref.of[IO, List[HttpRequest]](Nil)
      tokenState <- Ref.of[IO, String]("first")
      raw = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] =
          saved.update(_ :+ request).as(HttpResponse(200, "{}"))
      }
      tokens = new TokenProvider[IO] { def token: IO[String] = tokenState.get }
      client = new AuthenticatedTransport[IO](raw, tokens)
      _        <- client.execute(HttpRequest("GET", "/health/live", Map("authorization" -> "old", "x-app" -> "test")))
      _        <- tokenState.set("second")
      _        <- client.execute(HttpRequest("GET", "/health/live"))
      requests <- saved.get
    } yield {
      assertEquals(requests.map(_.headers("Authorization")), List("Bearer first", "Bearer second"))
      assert(!requests.head.headers.contains("authorization"))
      assertEquals(requests.head.headers("x-app"), "test")
    }
  }

  test("拒绝绝对 URL 和跨源路径") {
    List("https://example.com/", "//example.com/", "relative", "/x#y").foreach { path =>
      intercept[IllegalArgumentException](HttpRequest("GET", path))
    }
  }

  test("取消传播到注入的适配器并执行释放") {
    for {
      released <- Ref.of[IO, Boolean](false)
      started  <- cats.effect.Deferred[IO, Unit]
      raw = new HttpTransport[IO] {
        def execute(request: HttpRequest): IO[HttpResponse] =
          (started.complete(()) *> IO.never[HttpResponse]).guarantee(released.set(true))
      }
      tokens = new TokenProvider[IO] { def token: IO[String] = IO.pure("token") }
      client = new AuthenticatedTransport[IO](raw, tokens)
      fiber <- client.execute(HttpRequest("GET", "/health/live")).start
      _     <- started.get
      _     <- fiber.cancel
      value <- released.get
    } yield assert(value)
  }

  test("空凭证拒绝请求且不调用底层网络") {
    val raw = new HttpTransport[IO] {
      def execute(request: HttpRequest): IO[HttpResponse] = IO.raiseError(new AssertionError("network must not run"))
    }
    val tokens = new TokenProvider[IO] { def token: IO[String] = IO.pure(" ") }
    new AuthenticatedTransport[IO](raw, tokens).execute(HttpRequest("GET", "/health/live")).attempt.map { result =>
      assert(result.swap.toOption.get.isInstanceOf[IllegalArgumentException])
    }
  }

}
