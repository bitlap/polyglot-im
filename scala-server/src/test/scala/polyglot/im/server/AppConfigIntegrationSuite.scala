package polyglot.im.server

import polyglot.im.server.config.AppConfig
import polyglot.im.server.domain.Protocol

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite
import polyglot.im.{ApiClient, NotificationSender}
import polyglot.im.transport.*

class AppConfigIntegrationSuite extends CatsEffectSuite {
  override def munitIOTimeout = 60.seconds
  private def token(config: AppConfig, service: Boolean): String = {
    val header = Protocol.b64(Json.obj("alg" -> Json.fromString("HS256")).noSpaces.getBytes(UTF_8))
    val claims = Json.obj(
      "iss"        -> Json.fromString(config.auth.issuer),
      "aud"        -> Json.fromString("polyglot-im"),
      "app_id"     -> Json.fromString(config.auth.appId),
      "sub"        -> Json.fromString(if (service) "producer" else "alice"),
      "role"       -> Json.fromString(if (service) "service" else "user"),
      "exp"        -> Json.fromLong(Protocol.now + 600),
      "types"      -> Json.arr(Json.fromString("system.notice")),
      "recipients" -> Json.arr(Json.fromString("alice"))
    )
    val body = header + "." + Protocol.b64(claims.noSpaces.getBytes(UTF_8))
    body + "." + Protocol.hmac(if (service) config.auth.serviceJwtSecret else config.auth.userJwtSecret, body)
  }
  test("仅 HOCON 配置启动实际组合根，完成 PostgreSQL 初始化、JWT 鉴权和 HTTP 收发") {
    val resources = for {
      pg   <- Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
      file <- Resource.make(IO.blocking(Files.createTempFile("im-runtime", ".conf")))(p => IO.blocking(Files.delete(p)))
      _ <- Resource.eval(
        IO.blocking(
          Files.writeString(
            file,
            s"""
        polyglot-im {
          server { host="127.0.0.1", port=0 }
          database { url="jdbc:postgresql://127.0.0.1:${pg.getPort}/postgres", user="postgres", pool-size=2 }
          auth { app-id="hocon-runtime", user-jwt-secret="${"u" * 32}", service-jwt-secret="${"s" * 32}", cursor-secret="${"c" * 32}" }
        }
        """
          )
        )
      )
      config <- Resource.eval(
        IO.fromEither(AppConfig.load(Some(file), Map.empty, Map.empty).left.map(new IllegalArgumentException(_)))
      )
      server  <- Main.resource(config)
      network <- JdkTransport.resource[IO](URI.create("http://127.0.0.1:" + server.address.getPort))
      api = (service: Boolean) =>
        new ApiClient[IO](
          new AuthenticatedTransport(
            network._1,
            new TokenProvider[IO] {
              def token: IO[String] = IO.pure(AppConfigIntegrationSuite.this.token(config, service))
            }
          )
        )
    } yield (new NotificationSender[IO](api(true)), api(false))
    resources.use { case (sender, user) =>
      for {
        _     <- sender.send("alice", "config-message", "system.notice", Json.obj("text" -> Json.fromString("配置文件收发")))
        state <- user.request("GET", "/state")
        _ = assertEquals(state.hcursor.get[String]("latest_seq").toOption, Some("1"))
      } yield ()
    }
  }
}
