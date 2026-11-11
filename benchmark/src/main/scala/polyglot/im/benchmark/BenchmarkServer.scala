package polyglot.im.benchmark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import cats.effect.{IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import polyglot.im.server.*

/** 仅 loopback 的专用压测组合根：实际 JWT / API / Hikari / PostgreSQL，关闭 APNs。 */
object BenchmarkServer extends IOApp.Simple {
  private def jwt(role: String, sub: String, key: String): String = {
    val header = Protocol.b64("{\"alg\":\"HS256\"}".getBytes(UTF_8))
    val body = Protocol.b64(
      Json
        .obj(
          "iss"        -> Json.fromString("benchmark"),
          "aud"        -> Json.fromString("polyglot-im"),
          "app_id"     -> Json.fromString("benchmark"),
          "sub"        -> Json.fromString(sub),
          "role"       -> Json.fromString(role),
          "exp"        -> Json.fromLong(Protocol.now + 86400),
          "types"      -> Json.arr(Json.fromString("*")),
          "recipients" -> Json.arr(Json.fromString("*"))
        )
        .noSpaces
        .getBytes(UTF_8)
    )
    val content = header + "." + body
    content + "." + Protocol.hmac(key, content)
  }
  def run: IO[Unit] = (for {
    directory <- Resource.eval(
      IO.fromOption(sys.env.get("POLYGLOT_IM_BENCH_DIRECTORY").map(Path.of(_)))(
        new IllegalArgumentException("benchmark runner must provide a private runtime directory")
      )
    )
    pg <- Resource.make(
      IO.blocking(
        EmbeddedPostgres
          .builder()
          .setPort(0)
          .setServerConfig("fsync", "on")
          .setServerConfig("synchronous_commit", "on")
          .setServerConfig("full_page_writes", "on")
          .start()
      )
    )(p => IO.blocking(p.close()))
    pool <- Resource.make(IO.blocking {
      val cfg = new HikariConfig(); cfg.setDataSource(pg.getPostgresDatabase); cfg.setMaximumPoolSize(8);
      cfg.setAutoCommit(false)
      new HikariDataSource(cfg)
    })(p => IO.blocking(p.close()))
    store = new Store[IO](pool)
    _ <- Resource.eval(store.migrate)
    _ <- Resource.eval((0 until 256).toList.traverse_ { index =>
      val who = Identity("benchmark", s"bench-$index", "user", Long.MaxValue)
      store.register(who, "benchmark-ios") *> store.device(who, "benchmark-ios", Some(f"$index%064x"), false, false)
    })
    userKey    = Protocol.id + Protocol.id
    serviceKey = Protocol.id + Protocol.id
    auth       = new JwtAuthenticator[IO](userKey, serviceKey, "benchmark", "benchmark")
    limits <- Resource.eval(Ref.of[IO, Map[String, (Long, Int)]](Map.empty))
    api = new Api[IO](
      store,
      auth,
      new CursorCodec(Protocol.id + Protocol.id),
      limits,
      RateLimits(Int.MaxValue, Int.MaxValue)
    )
    ready = new Readiness[IO] { def check: IO[Boolean] = store.ready }
    server <- Server.websocketResource[IO](
      ServerConfig(Host.fromString("127.0.0.1").get, Port.fromInt(0).get),
      ws => Server.app(ready, api.routes(ws))
    )
    _ <- Resource.eval(IO.blocking {
      val connection = pool.getConnection
      val database =
        try {
          val statement = connection.createStatement()
          try {
            val result = statement.executeQuery(
              "SELECT version(),current_setting('fsync'),current_setting('synchronous_commit'),current_setting('full_page_writes'),current_setting('wal_sync_method')"
            )
            try {
              result.next();
              Json.obj(
                "version"            -> Json.fromString(result.getString(1)),
                "fsync"              -> Json.fromString(result.getString(2)),
                "synchronous_commit" -> Json.fromString(result.getString(3)),
                "full_page_writes"   -> Json.fromString(result.getString(4)),
                "wal_sync_method"    -> Json.fromString(result.getString(5))
              )
            } finally result.close()
          } finally statement.close()
        } finally connection.close()
      val users = (0 until 256).map(i =>
        Json.obj("id" -> Json.fromString(s"bench-$i"), "token" -> Json.fromString(jwt("user", s"bench-$i", userKey)))
      )
      val metadata = Json.obj(
        "java"                    -> Json.fromString(System.getProperty("java.version")),
        "jvm_processors"          -> Json.fromInt(Runtime.getRuntime.availableProcessors()),
        "max_heap_bytes"          -> Json.fromLong(Runtime.getRuntime.maxMemory()),
        "database_pool"           -> Json.fromInt(8),
        "rate_limit_per_minute"   -> Json.fromInt(Int.MaxValue),
        "apns_enabled"            -> Json.False,
        "outbox_jobs_per_message" -> Json.fromInt(1),
        "postgres"                -> database
      )
      val config = Json.obj(
        "base_url"      -> Json.fromString("http://127.0.0.1:" + server.address.getPort),
        "service_token" -> Json.fromString(jwt("service", "benchmark-producer", serviceKey)),
        "users"         -> Json.arr(users*),
        "server"        -> metadata
      )
      Files.writeString(directory.resolve("config.json"), config.spaces2)
    })
    _ <- Resource.eval(IO.println("Benchmark server ready (loopback; pool=8; benchmark rate limits; APNs disabled)."))
  } yield ()).useForever
}
