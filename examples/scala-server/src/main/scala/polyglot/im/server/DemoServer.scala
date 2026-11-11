package polyglot.im.server

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import cats.effect.{IO, IOApp, Ref, Resource}
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
    store = new Store[IO](pg.getPostgresDatabase)
    _ <- Resource.eval(store.migrate)
    userKey    = Protocol.id + Protocol.id
    serviceKey = Protocol.id + Protocol.id
    codec      = new CursorCodec(Protocol.id + Protocol.id)
    auth       = new JwtAuthenticator[IO](userKey, serviceKey, "demo", "demo")
    limits <- Resource.eval(Ref.of[IO, Map[String, (Long, Int)]](Map.empty))
    api   = new Api[IO](store, auth, codec, limits)
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
    })
    _ <- Resource.eval(IO.println("Demo ready; local config written. Database is temporary; APNs disabled."))
  } yield ()).useForever
}
