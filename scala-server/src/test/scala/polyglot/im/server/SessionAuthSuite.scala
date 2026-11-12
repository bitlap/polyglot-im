package polyglot.im.server

import polyglot.im.server.domain.{Identity, Protocol}
import polyglot.im.server.infrastructure.auth.{HttpSessionAuthenticator, JwtAuthenticator}
import polyglot.im.server.ports.UserAuthenticator
import cats.effect.IO
import cats.syntax.all.*
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URI}
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json
import munit.CatsEffectSuite

class SessionAuthSuite extends CatsEffectSuite {
  test("默认 HTTP 会话校验支持 App/小程序不透明令牌，撤销立即生效且不跟随重定向") {
    IO.blocking {
      val shared = java.nio.file.Files.readString(
        java.nio.file.Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", "introspection.json")
      )
      val body     = new java.util.concurrent.atomic.AtomicReference[String](shared)
      val status   = new java.util.concurrent.atomic.AtomicInteger(200)
      val received = new java.util.concurrent.atomic.AtomicReference[String]("")
      val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/identity",
        exchange => {
          received.set(exchange.getRequestHeaders.getFirst("Authorization"))
          val bytes = body.get().getBytes(UTF_8)
          exchange.getResponseHeaders.add("Location", "/redirected")
          exchange.sendResponseHeaders(status.get(), bytes.length)
          exchange.getResponseBody.write(bytes); exchange.close()
        }
      )
      server.start()
      (server, body, status, received)
    }.bracket { case (server, body, status, received) =>
      val auth = new HttpSessionAuthenticator[IO](
        URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/identity"),
        "snapflow",
        HttpClient.newHttpClient()
      )
      for {
        app <- auth.authenticateUser("opaque-app-session")
        _ = assertEquals(app.user, "alice")
        _ = assertEquals(received.get(), "Bearer opaque-app-session")
        mini <- auth.authenticateUser("opaque-mini-session")
        _ = assertEquals(mini.role, "user")
        _     <- IO(body.set(s"""{"active":true,"app_id":"other","user_id":"alice","exp":${Protocol.now + 120}}"""))
        wrong <- auth.authenticateUser("opaque-mini-session").attempt
        _ = assert(wrong.isLeft)
        _       <- IO(body.set("""{"active":true,"app_id":"snapflow","user_id":"alice","exp":1}"""))
        expired <- auth.authenticateUser("opaque-app-session").attempt
        _ = assert(expired.isLeft)
        _       <- IO(body.set("""{"user_id":"alice"}"""))
        partial <- auth.authenticateUser("opaque-app-session").attempt
        _ = assert(partial.isLeft)
        _       <- IO(status.set(401))
        revoked <- auth.authenticateUser("opaque-mini-session").attempt
        _ = assert(revoked.isLeft)
        _          <- IO(status.set(302))
        redirected <- auth.authenticateUser("opaque-app-session").attempt
        _ = assert(redirected.isLeft)
      } yield ()
    } { case (server, _, _, _) => IO.blocking(server.stop(0)) }
  }
  test("可插拔用户鉴权不能提升为服务身份或跨应用，opaque 模式关闭本地用户 JWT") {
    val head = Protocol.b64("{\"alg\":\"HS256\"}".getBytes(UTF_8))
    def jwt(role: String, key: String): String = {
      val body = Protocol.b64(
        Json
          .obj(
            "iss"    -> Json.fromString("issuer"),
            "aud"    -> Json.fromString("polyglot-im"),
            "app_id" -> Json.fromString("app"),
            "sub"    -> Json.fromString("alice"),
            "role"   -> Json.fromString(role),
            "exp"    -> Json.fromLong(Protocol.now + 60)
          )
          .noSpaces
          .getBytes(UTF_8)
      )
      val message = head + "." + body
      message + "." + Protocol.hmac(key, message)
    }
    def auth(identity: Identity) = new JwtAuthenticator[IO](
      "u" * 32,
      "s" * 32,
      "issuer",
      "app",
      userAuthenticator = Some(new UserAuthenticator[IO] {
        def authenticateUser(token: String): IO[Identity] =
          if (token == "opaque") IO.pure(identity) else IO.raiseError(new IllegalArgumentException())
      })
    )
    val who = Identity("app", "alice", "user", Protocol.now + 60)
    for {
      accepted <- auth(who).authenticate("opaque")
      _ = assertEquals(accepted, who)
      jwtUser <- auth(who).authenticate(jwt("user", "u" * 32)).attempt
      _ = assert(jwtUser.isLeft)
      service <- auth(who).authenticate(jwt("service", "s" * 32))
      _ = assertEquals(service.role, "service")
      escalated <- auth(who.copy(role = "service")).authenticate("opaque").attempt
      crossed   <- auth(who.copy(app = "other")).authenticate("opaque").attempt
      _ = assert(escalated.isLeft && crossed.isLeft)
    } yield ()
  }
}
