package polyglot.im.server

import java.nio.file.{Files, Path, StandardCopyOption}
import cats.effect.{IO, IOApp, Resource}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

/** Python 数据库测试专用；临时目录由调用者创建，不使用任何生产配置。 */
object PostgresTestServer extends IOApp.Simple {
  def run: IO[Unit] =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close())).use { pg =>
      IO.blocking {
        val directory = Path.of(sys.env("POLYGLOT_IM_TEST_DIRECTORY"))
        // 先写临时文件再发布，避免 Python 在文件创建后、内容写入前读到空 DSN。
        val pending = directory.resolve("dsn.pending")
        Files.writeString(pending, s"host=127.0.0.1 port=${pg.getPort} dbname=postgres user=postgres")
        Files.move(pending, directory.resolve("dsn"), StandardCopyOption.ATOMIC_MOVE)
      } *> IO.never
    }
}
