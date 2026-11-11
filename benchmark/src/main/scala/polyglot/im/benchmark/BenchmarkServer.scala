package polyglot.im.benchmark

import polyglot.im.server.Main
import polyglot.im.server.config.AppConfig
import polyglot.im.server.config.AuthSettings
import polyglot.im.server.config.DatabaseConfig
import polyglot.im.server.config.RateLimits
import polyglot.im.server.config.ServerConfig
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol
import polyglot.im.server.infrastructure.postgres.PostgresStore
import polyglot.im.server.service.PushWorker

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

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
    store = new PostgresStore[IO](pg.getPostgresDatabase)
    _ <- Resource.eval(store.initialize)
    _ <- Resource.eval((0 until 256).toList.traverse_ { index =>
      val who = Identity("benchmark", s"bench-$index", "user", Long.MaxValue)
      store.register(who, "benchmark-ios") *> store.device(who, "benchmark-ios", Some(f"$index%064x"), false, false)
    })
    userKey    = Protocol.id + Protocol.id
    serviceKey = Protocol.id + Protocol.id
    instances  = sys.env.get("POLYGLOT_IM_BENCH_INSTANCES").flatMap(_.toIntOption).getOrElse(1)
    _ <- Resource.eval(IO(require(instances >= 1 && instances <= 8, "instances must be 1..8")))
    config = AppConfig(
      ServerConfig(Host.fromString("127.0.0.1").get, Port.fromInt(0).get),
      DatabaseConfig(s"jdbc:postgresql://127.0.0.1:${pg.getPort}/postgres", "postgres", "", 8),
      AuthSettings("benchmark", "benchmark", userKey, serviceKey, Protocol.id + Protocol.id, None),
      None,
      None,
      RateLimits(Int.MaxValue, Int.MaxValue),
      256,
      PushWorker.defaults
    )
    servers <- List.fill(instances)(()).traverse(_ => Main.resource(config))
    _ <- Resource.eval(IO.blocking {
      val connection = pg.getPostgresDatabase.getConnection
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
        "instances"               -> Json.fromInt(instances),
        "topology"                -> Json.fromString("same-jvm-independent-resources"),
        "changefeed"              -> Json.fromString("postgres-notify-with-5s-reconciliation"),
        "rate_limiter"            -> Json.fromString("postgres-global"),
        "rate_limit_per_minute"   -> Json.fromInt(Int.MaxValue),
        "apns_enabled"            -> Json.False,
        "outbox_jobs_per_message" -> Json.fromInt(1),
        "postgres"                -> database
      )
      val config = Json.obj(
        "base_url"  -> Json.fromString("http://127.0.0.1:" + servers.head.address.getPort),
        "base_urls" -> Json.arr(servers.map(server => Json.fromString("http://127.0.0.1:" + server.address.getPort))*),
        "service_token" -> Json.fromString(jwt("service", "benchmark-producer", serviceKey)),
        "users"         -> Json.arr(users*),
        "server"        -> metadata
      )
      Files.writeString(directory.resolve("config.json"), config.spaces2)
    })
    _ <- Resource.eval(IO.println("Benchmark server ready (loopback; pool=8; benchmark rate limits; APNs disabled)."))
  } yield ()).useForever
}
