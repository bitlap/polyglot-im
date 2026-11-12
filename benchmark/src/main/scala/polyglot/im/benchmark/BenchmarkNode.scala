package polyglot.im.benchmark

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.parse
import com.comcast.ip4s.{Host, Port}
import com.zaxxer.hikari.{HikariDataSource, metrics}
import polyglot.im.server.Main
import polyglot.im.server.bootstrap.ServerComponents
import polyglot.im.server.config.*
import polyglot.im.server.infrastructure.postgres.PostgresStore
import polyglot.im.server.service.PushWorker

/** 记录连接获取等待的累计次数、耗时、最大值与超时；只用于压测观测。 */
final class PoolMeasurements extends metrics.MetricsTrackerFactory {
  private val count    = new AtomicLong()
  private val nanos    = new AtomicLong()
  private val maximum  = new AtomicLong()
  private val timeouts = new AtomicLong()

  /** 为 Hikari 创建轻量计数器，不记录 SQL、凭证或业务正文。 */
  def create(name: String, stats: metrics.PoolStats): metrics.IMetricsTracker = new metrics.IMetricsTracker {
    override def recordConnectionAcquiredNanos(value: Long): Unit = {
      count.incrementAndGet(); nanos.addAndGet(value); maximum.accumulateAndGet(value, math.max); ()
    }
    override def recordConnectionTimeout(): Unit = { timeouts.incrementAndGet(); () }
  }

  /** 返回累计连接获取等待与当前池占用；前后快照的差值用于计算每轮均值。 */
  def snapshot(pool: HikariDataSource): Json = {
    val bean = pool.getHikariPoolMXBean
    Json.obj(
      "acquisitions"                  -> Json.fromLong(count.get()),
      "acquire_nanos"                 -> Json.fromLong(nanos.get()),
      "max_acquire_nanos_since_start" -> Json.fromLong(maximum.get()),
      "timeouts"                      -> Json.fromLong(timeouts.get()),
      "active"                        -> Json.fromInt(bean.getActiveConnections),
      "idle"                          -> Json.fromInt(bean.getIdleConnections),
      "waiting"                       -> Json.fromInt(bean.getThreadsAwaitingConnection),
      "maximum"                       -> Json.fromInt(pool.getMaximumPoolSize)
    )
  }
}

/** 压测子进程的独立服务；使用生产组合根、鉴权、存储、限流与 WS 实现。 */
object BenchmarkNode extends IOApp.Simple {
  private def value(json: Json, name: String): String = json.hcursor.get[String](name).toOption.get
  private def integer(json: Json, name: String): Int  = json.hcursor.get[Int](name).toOption.get

  /** 原子写入不含凭证的节点就绪信息或运行快照，避免读取半个 JSON。 */
  def write(path: Path, json: Json): Unit = {
    val temporary = path.resolveSibling(path.getFileName.toString + ".tmp")
    Files.writeString(temporary, json.noSpaces, UTF_8)
    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); ()
  }

  private def snapshot(index: Int, pool: HikariDataSource, measurements: PoolMeasurements): Json = {
    val os   = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
    val gc   = ManagementFactory.getGarbageCollectorMXBeans.asScala
    Json.obj(
      "node"              -> Json.fromInt(index),
      "pid"               -> Json.fromLong(ProcessHandle.current().pid()),
      "timestamp_ms"      -> Json.fromLong(System.currentTimeMillis()),
      "process_cpu_nanos" -> Json.fromLong(os.getProcessCpuTime),
      "heap_used_bytes"   -> Json.fromLong(heap.getUsed),
      "gc_count"          -> Json.fromLong(gc.map(_.getCollectionCount.max(0L)).sum),
      "gc_millis"         -> Json.fromLong(gc.map(_.getCollectionTime.max(0L)).sum),
      "pool"              -> measurements.snapshot(pool)
    )
  }

  /** 读取私有启动文件并运行服务；取消时依次停止采样、HTTP 与数据库连接池。 */
  def run: IO[Unit] = (for {
    path <- Resource.eval(
      IO.fromOption(sys.env.get("POLYGLOT_IM_BENCH_NODE_FILE").map(Path.of(_)))(
        new IllegalArgumentException("benchmark node requires a private launch file")
      )
    )
    launch <- Resource.eval(IO.blocking(parse(Files.readString(path)).toOption.get))
    index    = integer(launch, "index")
    database = DatabaseConfig(value(launch, "database_url"), "postgres", "", integer(launch, "pool"))
    config = AppConfig(
      ServerConfig(Host.fromString(value(launch, "bind")).get, Port.fromInt(0).get),
      database,
      AuthSettings(
        "benchmark",
        "benchmark",
        value(launch, "user_key"),
        value(launch, "service_key"),
        value(launch, "cursor_key"),
        None
      ),
      None,
      None,
      RateLimits(Int.MaxValue, Int.MaxValue),
      256,
      PushWorker.defaults
    )
    pool <- PostgresStore.dataSourceResource[IO](database)
    measurements = new PoolMeasurements
    _ <- Resource.eval(IO.blocking(pool.setMetricsTrackerFactory(measurements)))
    components = ServerComponents.postgres[IO](config).copy(repository = Resource.pure(new PostgresStore[IO](pool)))
    server <- Main.resource(config, components)
    _ <- Resource.eval(
      IO.blocking(
        write(
          Path.of(value(launch, "ready")),
          Json.obj(
            "pid"            -> Json.fromLong(ProcessHandle.current().pid()),
            "port"           -> Json.fromInt(server.address.getPort),
            "java"           -> Json.fromString(System.getProperty("java.version")),
            "jvm_processors" -> Json.fromInt(Runtime.getRuntime.availableProcessors()),
            "max_heap_bytes" -> Json.fromLong(Runtime.getRuntime.maxMemory()),
            "pool"           -> Json.fromInt(database.maxPoolSize)
          )
        )
      )
    )
    sample = IO.blocking(write(Path.of(value(launch, "metrics")), snapshot(index, pool, measurements)))
    _ <- Resource.eval(sample)
    _ <- Resource.make((Stream.awakeEvery[IO](500.millis).evalMap(_ => sample).compile.drain).start)(_.cancel)
  } yield ()).useForever
}
