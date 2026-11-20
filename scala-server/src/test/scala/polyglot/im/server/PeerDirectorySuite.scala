package polyglot.im.server

import cats.effect.IO
import cats.syntax.all.*
import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import munit.CatsEffectSuite
import polyglot.im.server.domain.{ApiError, Identity}
import polyglot.im.server.infrastructure.directory.HttpPeerDirectory

/** 固定目录逐次校验身份，失效身份及重定向不能降级放行。 */
class PeerDirectorySuite extends CatsEffectSuite {
  test("业务关系不缓存授权，校验活跃身份和响应范围，不跟随重定向") {
    val valid =
      """{"data":{"active":true,"app_id":"app","user_id":"alice","items":[{"user_id":"bob","exists":true,"following":true,"follows_you":true}]}}"""
    IO.blocking {
      val body     = new AtomicReference(valid)
      val status   = new AtomicInteger(200)
      val received = new AtomicReference("")
      val server   = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      server.createContext(
        "/relations",
        exchange => {
          received.set(exchange.getRequestHeaders.getFirst("Authorization"))
          val bytes = body.get().getBytes(UTF_8)
          exchange.getResponseHeaders.add("Location", "/redirected")
          exchange.sendResponseHeaders(status.get(), bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
        }
      )
      server.start()
      (server, body, status, received)
    }.bracket { case (server, body, status, received) =>
      HttpPeerDirectory.resource[IO](URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/relations")).use {
        directory =>
          val who = Identity("app", "alice", "user", Long.MaxValue)
          for {
            relation <- directory.relationships(who, List("bob"), "opaque-session")
            _ = assert(relation("bob").mutual)
            _ = assertEquals(received.get(), "Bearer opaque-session")
            _       <- IO(body.set(valid.replace("\"active\":true", "\"active\":false")))
            revoked <- directory.relationships(who, List("bob"), "opaque-session").attempt
            _ = assertEquals(revoked.swap.toOption.get.asInstanceOf[ApiError].status, 503)
            _     <- IO(body.set(valid.replace("alice", "other")))
            wrong <- directory.relationships(who, List("bob"), "opaque-session").attempt
            _ = assert(wrong.isLeft)
            _           <- IO(body.set(valid.replace("bob", "not-requested")))
            unrequested <- directory.relationships(who, List("bob"), "opaque-session").attempt
            _ = assert(unrequested.isLeft)
            _          <- IO(body.set(valid)) *> IO(status.set(302))
            redirected <- directory.relationships(who, List("bob"), "opaque-session").attempt
            _ = assert(redirected.isLeft)
          } yield ()
      }
    } { case (server, _, _, _) => IO.blocking(server.stop(0)) }
  }
}
