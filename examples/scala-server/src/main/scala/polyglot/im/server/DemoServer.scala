package polyglot.im.server

import polyglot.im.server.api.http.Api
import polyglot.im.server.api.http.Server
import polyglot.im.server.config.ServerConfig
import polyglot.im.server.domain.Protocol
import polyglot.im.server.infrastructure.auth.JwtAuthenticator
import polyglot.im.server.infrastructure.postgres.ChangeFeed
import polyglot.im.server.infrastructure.postgres.PostgresStore
import polyglot.im.server.ports.Readiness
import polyglot.im.server.security.CursorCodec
import polyglot.im.server.service.RateLimiter as RateLimiters

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** 仅本机开发：临时真实数据库，随机密钥，无 APNs；退出释放数据库。 */
object DemoServer extends IOApp.Simple {
  private def jwt(role: String, sub: String, key: String): String = {
    val head = Protocol.b64("{\"alg\":\"HS256\"}".getBytes(UTF_8))
    val body = Protocol.b64(
      Json
        .obj(
          "iss"        -> Json.fromString("demo"),
          "aud"        -> Json.fromString("polyglot-im"),
          "app_id"     -> Json.fromString("demo"),
          "sub"        -> Json.fromString(sub),
          "role"       -> Json.fromString(role),
          "exp"        -> Json.fromLong(Protocol.now + 86400),
          "types"      -> Json.arr(Json.fromString("*")),
          "recipients" -> Json.arr(Json.fromString("alice"))
        )
        .noSpaces
        .getBytes(UTF_8)
    )
    val content = head + "." + body; content + "." + Protocol.hmac(key, content)
  }
  def run: IO[Unit] = (for {
    pg <- Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
    store = new PostgresStore[IO](pg.getPostgresDatabase)
    _ <- Resource.eval(store.initialize)
    userKey    = Protocol.id + Protocol.id
    serviceKey = Protocol.id + Protocol.id
    codec      = new CursorCodec(Protocol.id + Protocol.id)
    auth <- JwtAuthenticator.resource[IO](
      userKey,
      serviceKey,
      "demo",
      "demo",
      sys.env.get("POLYGLOT_IM_DEMO_INTROSPECTION_URL").map(value => (java.net.URI.create(value), "demo"))
    )
    changes <- ChangeFeed.resource[IO](IO.blocking(pg.getPostgresDatabase.getConnection))
    api   = new Api[IO](store, auth, codec, RateLimiters.postgres(store), changes = Some(changes))
    ready = new Readiness[IO] { def check: IO[Boolean] = store.ready }
    port  = sys.env.get("POLYGLOT_IM_DEMO_PORT").flatMap(_.toIntOption).getOrElse(0)
    server <- Server.websocketResource[IO](
      ServerConfig(Host.fromString("127.0.0.1").get, Port.fromInt(port).get),
      ws => Server.app(ready, api.routes(ws))
    )
    _ <- Resource.eval(IO.blocking {
      val directory = Path.of(
        sys.env.getOrElse(
          "POLYGLOT_IM_DEMO_DIRECTORY",
          Path.of(sys.props.getOrElse("polyglot.im.root", "."), ".local-demo").toString
        )
      )
      Files.createDirectories(directory)
      Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
      val base = "http://127.0.0.1:" + server.address.getPort
      val user = jwt("user", "alice", userKey)
      val config = Json.obj(
        "base_url"      -> Json.fromString(base),
        "app_id"        -> Json.fromString("demo"),
        "user_id"       -> Json.fromString("alice"),
        "user_token"    -> Json.fromString(user),
        "bob_token"     -> Json.fromString(jwt("user", "bob", userKey)),
        "service_token" -> Json.fromString(jwt("service", "demo-producer", serviceKey))
      )
      val flutter = Json.obj(
        "IM_BASE_URL"   -> Json.fromString(base),
        "IM_APP_ID"     -> Json.fromString("demo"),
        "IM_USER_ID"    -> Json.fromString("alice"),
        "IM_USER_TOKEN" -> Json.fromString(user)
      )
      Files.writeString(directory.resolve("config.json"), config.spaces2)
      Files.writeString(directory.resolve("flutter.json"), flutter.spaces2)
      Files.writeString(
        directory.resolve("flutter-bob.json"),
        flutter
          .deepMerge(
            Json.obj(
              "IM_USER_ID"    -> Json.fromString("bob"),
              "IM_USER_TOKEN" -> Json.fromString(jwt("user", "bob", userKey))
            )
          )
          .spaces2
      )
    })
    _ <- Resource.eval(IO.println("Demo ready; local config written. Database is temporary; APNs disabled."))
  } yield ()).useForever
}
