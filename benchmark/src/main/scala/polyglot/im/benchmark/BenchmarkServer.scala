package polyglot.im.benchmark

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.security.MessageDigest
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.circe.parser.parse
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.http4s.{HttpRoutes, Header}
import org.http4s.dsl.io.*
import org.http4s.circe.*
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci.CIString
import polyglot.im.server.domain.{Identity, Protocol}
import polyglot.im.server.infrastructure.postgres.PostgresStore

/** 启动临时数据库和独立 JVM 服务；向独立发压端提供私有接入文件及只读观测端点。 */
object BenchmarkServer extends IOApp.Simple {
  private def setting(name: String, default: String): String = sys.env.getOrElse("POLYGLOT_IM_BENCH_" + name, default)
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
    header + "." + body + "." + Protocol.hmac(key, header + "." + body)
  }
  private def query(c: Connection, sql: String): Json = {
    val statement = c.createStatement()
    try {
      val rows = statement.executeQuery(sql)
      try {
        val out  = List.newBuilder[Json]
        val meta = rows.getMetaData
        while (rows.next())
          out += Json.obj(
            (1 to meta.getColumnCount)
              .map(i => meta.getColumnLabel(i) -> Option(rows.getString(i)).fold(Json.Null)(Json.fromString))*
          )
        Json.arr(out.result()*)
      } finally rows.close()
    } finally statement.close()
  }
  private def databaseSnapshot(c: Connection): Json = {
    val backend =
      query(c, "SELECT pg_backend_pid() AS pid").asArray.get.head.hcursor.get[String]("pid").toOption.get.toLong
    val root     = ProcessHandle.of(backend).get().parent().get()
    val children = root.descendants()
    val handles =
      try root :: children.iterator().asScala.toList
      finally children.close()
    val nativeCpu = handles.flatMap { process =>
      val duration = process.info().totalCpuDuration()
      if (duration.isPresent) Some(process.pid().toString -> Json.fromLong(duration.get().toNanos)) else None
    }
    // macOS 的 ProcessHandle 可能不提供 CPU 时间；只采集 ps 的 PID/父 PID/CPU 时间列。
    val cpu =
      if (nativeCpu.size == handles.size) nativeCpu
      else {
        val process = new ProcessBuilder("ps", "-axo", "pid=,ppid=,time=").start()
        val text    = scala.util.Using.resource(scala.io.Source.fromInputStream(process.getInputStream))(_.mkString)
        require(process.waitFor() == 0, "cannot sample PostgreSQL process CPU")
        val records = text.linesIterator.flatMap { line =>
          val columns = line.trim.split("\\s+")
          if (columns.length != 3) None
          else
            scala.util.Try {
              val clock   = columns(2).split("-", 2)
              val days    = if (clock.length == 2) clock(0).toDouble * 86400 else 0d
              val seconds = clock.last.split(":").foldLeft(0d)((total, part) => total * 60 + part.toDouble) + days
              (columns(0).toLong, columns(1).toLong, (seconds * 1e9).toLong)
            }.toOption
        }.toList
        var selected = Set(root.pid())
        var expanded = selected ++ records.filter(row => selected(row._2)).map(_._1)
        while (selected != expanded) {
          selected = expanded
          expanded = selected ++ records.filter(row => selected(row._2)).map(_._1)
        }
        records.filter(row => selected(row._1)).map(row => row._1.toString -> Json.fromLong(row._3))
      }
    Json.obj(
      "process_cpu_nanos"  -> Json.obj(cpu*),
      "process_cpu_source" -> Json.fromString(if (nativeCpu.size == handles.size) "process-handle" else "ps"),
      "activity" -> query(
        c,
        "SELECT state,wait_event_type,wait_event,count(*) AS connections,count(*) FILTER(WHERE cardinality(pg_blocking_pids(pid))>0) AS blocked FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() GROUP BY state,wait_event_type,wait_event"
      ),
      "wal" -> query(
        c,
        "SELECT wal_records,wal_fpi,wal_bytes,wal_buffers_full,wal_write,wal_sync,wal_write_time,wal_sync_time,stats_reset FROM pg_stat_wal"
      ),
      "database" -> query(
        c,
        "SELECT xact_commit,xact_rollback,blks_read,blks_hit,blk_read_time,blk_write_time,deadlocks,temp_bytes,stats_reset FROM pg_stat_database WHERE datname=current_database()"
      )
    )
  }
  private def node(directory: Path, index: Int, launch: Json, cpus: Int, heap: Int): Resource[IO, Json] = for {
    process <- Resource.make(IO.blocking {
      val input = directory.resolve(s"node-$index.local.conf")
      Files.writeString(input, launch.noSpaces)
      Files.setPosixFilePermissions(input, PosixFilePermissions.fromString("rw-------"))
      val builder = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString,
        "-Xms64m",
        s"-Xmx${heap}m",
        s"-XX:ActiveProcessorCount=$cpus",
        "-cp",
        System.getProperty("java.class.path"),
        "polyglot.im.benchmark.BenchmarkNode"
      )
      builder.environment().put("POLYGLOT_IM_BENCH_NODE_FILE", input.toString)
      builder.redirectErrorStream(true).redirectOutput(directory.resolve(s"node-$index.log").toFile).start()
    })(process =>
      IO.blocking {
        process.destroy()
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
          process.destroyForcibly(); process.waitFor()
        }
        ()
      }
    )
    ready = directory.resolve(s"node-$index.ready.json")
    data <- Resource.eval(
      Stream
        .repeatEval(IO.blocking {
          require(process.isAlive, s"benchmark node $index exited; inspect its private log")
          if (Files.exists(ready) && Files.exists(directory.resolve(s"node-$index.metrics.json")))
            Some(parse(Files.readString(ready)).toOption.get)
          else None
        })
        .metered(100.millis)
        .unNone
        .take(1)
        .compile
        .lastOrError
        .timeout(90.seconds)
    )
  } yield data

  /** 在显式指定的私有目录启动夹具；子 JVM、采样和数据库均随取消释放。 */
  def run: IO[Unit] = (for {
    directory <- Resource.eval(IO.blocking {
      require(setting("DIRECTORY", "").nonEmpty, "private benchmark directory required")
      val path = Path.of(setting("DIRECTORY", ""))
      Files.createDirectories(path)
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); path
    })
    instances  = setting("INSTANCES", "1").toInt
    totalPool  = setting("DB_CONNECTIONS", "8").toInt
    totalCpus  = setting("SERVER_CPUS", "4").toInt
    totalHeap  = setting("SERVER_HEAP_MIB", "2048").toInt
    producers  = setting("PRODUCERS", "16").toInt
    users      = setting("USERS", "256").toInt
    bind       = setting("BIND", "127.0.0.1")
    advertised = setting("ADVERTISE_HOST", "127.0.0.1")
    _ <- Resource.eval(IO {
      require(
        instances >= 1 && instances <= 8 && totalPool >= instances && totalCpus >= instances && totalHeap >= instances * 128
      )
      require(producers > 0 && producers <= 256 && users > 0 && users <= 256)
      require(
        !advertised.exists(c => c == '/' || c == '?' || c == '#'),
        "advertise host must be a host name or address"
      )
    })
    pg <- Resource.make(
      IO.blocking(
        EmbeddedPostgres
          .builder()
          .setPort(0)
          .setServerConfig("fsync", "on")
          .setServerConfig("synchronous_commit", "on")
          .setServerConfig("full_page_writes", "on")
          .setServerConfig("track_io_timing", "on")
          .setServerConfig("track_wal_io_timing", "on")
          .start()
      )
    )(p => IO.blocking(p.close()))
    store = new PostgresStore[IO](pg.getPostgresDatabase)
    _ <- Resource.eval(store.initialize)
    _ <- Resource.eval((0 until users).toList.traverse_ { index =>
      val who = Identity("benchmark", s"bench-$index", "user", Long.MaxValue)
      store.register(who, "benchmark-ios") *> store.device(who, "benchmark-ios", Some(f"$index%064x"), false, false)
    })
    userKey    = Protocol.id + Protocol.id
    serviceKey = Protocol.id + Protocol.id
    cursorKey  = Protocol.id + Protocol.id
    token      = Protocol.id + Protocol.id
    budgets = (0 until instances).toList.map { index =>
      def share(total: Int): Int = total / instances + (if (index < total % instances) 1 else 0)
      (share(totalPool), share(totalCpus), share(totalHeap))
    }
    nodes <- budgets.zipWithIndex.traverse { case ((pool, cpus, heap), index) =>
      val launch = Json.obj(
        "index" -> Json.fromInt(index),
        "bind"  -> Json.fromString(bind),
        "pool"  -> Json.fromInt(pool),
        "database_url" -> Json.fromString(
          s"jdbc:postgresql://127.0.0.1:${pg.getPort}/postgres?ApplicationName=benchmark-node-$index"
        ),
        "user_key"    -> Json.fromString(userKey),
        "service_key" -> Json.fromString(serviceKey),
        "cursor_key"  -> Json.fromString(cursorKey),
        "ready"       -> Json.fromString(directory.resolve(s"node-$index.ready.json").toString),
        "metrics"     -> Json.fromString(directory.resolve(s"node-$index.metrics.json").toString)
      )
      node(directory, index, launch, cpus, heap)
    }
    connection <- Resource.make(IO.blocking { val c = pg.getPostgresDatabase.getConnection; c.setAutoCommit(true); c })(
      c => IO.blocking(c.close())
    )
    postgres <- Resource.eval(
      IO.blocking(
        query(
          connection,
          "SELECT version() AS version,current_setting('fsync') AS fsync,current_setting('synchronous_commit') AS synchronous_commit,current_setting('full_page_writes') AS full_page_writes,current_setting('wal_sync_method') AS wal_sync_method,current_setting('track_io_timing') AS track_io_timing,current_setting('track_wal_io_timing') AS track_wal_io_timing"
        ).asArray.get.head
      )
    )
    current <- Resource.eval(Ref.of[IO, Json](Json.obj()))
    sample = IO
      .blocking {
        val values = nodes.indices.map { index =>
          val value = parse(Files.readString(directory.resolve(s"node-$index.metrics.json"))).toOption.get
          val pid   = value.hcursor.get[Long]("pid").toOption.get
          require(
            ProcessHandle.of(pid).isPresent && ProcessHandle.of(pid).get().isAlive,
            s"benchmark node $index stopped"
          )
          value
        }
        Json.obj(
          "timestamp_ms" -> Json.fromLong(System.currentTimeMillis()),
          "nodes"        -> Json.arr(values*),
          "postgres"     -> databaseSnapshot(connection)
        )
      }
      .flatTap(current.set)
    _       <- Resource.eval(sample)
    monitor <- Resource.make(Stream.awakeEvery[IO](500.millis).evalMap(_ => sample).compile.drain.start)(_.cancel)
    routes = HttpRoutes.of[IO] { case request @ GET -> Root / "metrics" =>
      val supplied = request.headers.get(CIString("Authorization")).map(_.head.value).getOrElse("")
      if (MessageDigest.isEqual(supplied.getBytes(UTF_8), ("Bearer " + token).getBytes(UTF_8)))
        current.get.flatMap(value => Ok(value).map(_.putHeaders(Header.Raw(CIString("Cache-Control"), "no-store"))))
      else
        IO.pure(
          org.http4s
            .Response[IO](org.http4s.Status.Unauthorized)
            .withEntity(Json.obj("error" -> Json.fromString("benchmark authentication required")))
        )
    }
    diagnostics <- EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(bind).get)
      .withPort(Port.fromInt(0).get)
      .withHttpApp(routes.orNotFound)
      .build
    _ <- Resource.eval(IO.blocking {
      def url(port: Int): String = new java.net.URI("http", null, advertised, port, null, null, null).toString
      val urls                   = nodes.map(node => Json.fromString(url(node.hcursor.get[Int]("port").toOption.get)))
      val metadata = Json.obj(
        "java"                       -> Json.fromString(System.getProperty("java.version")),
        "instances"                  -> Json.fromInt(instances),
        "topology"                   -> Json.fromString("independent-jvms-shared-host-database"),
        "nodes"                      -> Json.arr(nodes*),
        "database_connections_total" -> Json.fromInt(totalPool),
        "database_pool_per_node"     -> Json.arr(budgets.map(b => Json.fromInt(b._1))*),
        "server_cpus_total"          -> Json.fromInt(totalCpus),
        "server_heap_mib_total"      -> Json.fromInt(totalHeap),
        "host"                       -> Json.fromString(java.net.InetAddress.getLocalHost.getHostName),
        "coordinator_pid"            -> Json.fromLong(ProcessHandle.current().pid()),
        "changefeed"                 -> Json.fromString("postgres-notify-with-5s-reconciliation"),
        "rate_limiter"               -> Json.fromString("postgres-global"),
        "rate_limit_per_minute"      -> Json.fromInt(Int.MaxValue),
        "apns_enabled"               -> Json.False,
        "outbox_jobs_per_message"    -> Json.fromInt(1),
        "postgres"                   -> postgres
      )
      val manifest = Json.obj(
        "manifest_version"  -> Json.fromInt(2),
        "base_url"          -> urls.head,
        "base_urls"         -> Json.arr(urls*),
        "diagnostics_url"   -> Json.fromString(url(diagnostics.address.getPort) + "/metrics"),
        "diagnostics_token" -> Json.fromString(token),
        "service_tokens" -> Json.arr(
          (0 until producers).map(i => Json.fromString(jwt("service", s"benchmark-producer-$i", serviceKey)))*
        ),
        "users" -> Json.arr(
          (0 until users).map(i =>
            Json
              .obj("id" -> Json.fromString(s"bench-$i"), "token" -> Json.fromString(jwt("user", s"bench-$i", userKey)))
          )*
        ),
        "server" -> metadata
      )
      val output = directory.resolve("config.json")
      BenchmarkNode.write(output, manifest)
      Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------"))
    })
    _ <- Resource.eval(
      IO.println("Benchmark fixture ready; access credentials are in the private config.json, never in results.")
    )
  } yield monitor).use(_.joinWithNever)
}
