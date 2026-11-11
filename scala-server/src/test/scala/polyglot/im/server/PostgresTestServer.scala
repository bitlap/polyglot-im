package polyglot.im.server

import java.nio.file.{Files, Path}
import cats.effect.{IO, IOApp, Resource}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** Python 数据库测试专用；临时目录由调用者创建，不使用任何生产配置。 */
object PostgresTestServer extends IOApp.Simple {
  def run: IO[Unit] =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close())).use { pg =>
      IO.blocking {
        val directory = Path.of(sys.env("POLYGLOT_IM_TEST_DIRECTORY"))
        Files.writeString(directory.resolve("dsn"), s"host=127.0.0.1 port=${pg.getPort} dbname=postgres user=postgres")
      } *> IO.never
    }
}
