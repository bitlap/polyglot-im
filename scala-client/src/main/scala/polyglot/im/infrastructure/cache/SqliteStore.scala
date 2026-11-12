package polyglot.im.infrastructure.cache

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager, SQLException}
import scala.util.Using
import scala.util.control.NonFatal
import cats.effect.Async
import io.circe.Json
import io.circe.parser.parse
import polyglot.im.domain.{AccountScope, LocalCache}
import polyglot.im.ports.LocalStore

/** JVM 本地 SQLite 缓存；消息与游标在同一事务提交，每次操作独立打开并关闭连接。 */
final class SqliteStore[F[_]: Async](path: Path) extends LocalStore[F] {
  require(
    path.toString.nonEmpty && path.toString != ":memory:" && !path.toString.startsWith("file:"),
    "a persistent database path is required"
  )
  private val database = path.toAbsolutePath.normalize()

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement()) { statement =>
      statement.execute(sql); ()
    }

  private def withDatabase[A](write: Boolean)(run: Connection => A): F[A] = Async[F].blocking {
    Files.createDirectories(database.getParent)
    Using.resource(DriverManager.getConnection("jdbc:sqlite:" + database.toUri.toASCIIString)) { connection =>
      configureJournal(connection)
      execute(connection, "PRAGMA synchronous=FULL")
      execute(connection, "CREATE TABLE IF NOT EXISTS im_cache(scope TEXT PRIMARY KEY, data TEXT NOT NULL)")
      if (!write) run(connection)
      else {
        execute(connection, "BEGIN IMMEDIATE")
        try {
          val result = run(connection)
          execute(connection, "COMMIT")
          result
        } catch {
          case NonFatal(error) =>
            try execute(connection, "ROLLBACK")
            catch { case NonFatal(rollback) => error.addSuppressed(rollback) }
            throw error
        }
      }
    }
  }

  private def configureJournal(connection: Connection): Unit = {
    // 首次并发切换 WAL 可能立即返回 SQLITE_BUSY，按剩余时间有限重试，不重放业务事务。
    val deadline = System.nanoTime() + 15000000000L
    var ready    = false
    while (!ready) {
      val remaining = math.max(1L, (deadline - System.nanoTime()) / 1000000L)
      execute(connection, s"PRAGMA busy_timeout=$remaining")
      try { execute(connection, "PRAGMA journal_mode=WAL"); ready = true }
      catch {
        case error: SQLException if (error.getErrorCode & 255) == 5 && System.nanoTime() < deadline =>
          Thread.sleep(10)
      }
    }
    execute(connection, "PRAGMA busy_timeout=15000")
  }

  private def read(connection: Connection, scope: AccountScope): Json =
    Using.resource(connection.prepareStatement("SELECT data FROM im_cache WHERE scope=?")) { statement =>
      statement.setString(1, scope.key)
      Using.resource(statement.executeQuery()) { rows =>
        if (!rows.next()) LocalCache.empty
        else parse(rows.getString(1)).fold(error => throw new IllegalStateException("corrupt cache", error), identity)
      }
    }

  /** 读取指定账号/环境的持久缓存；WAL 下普通读取不占用写事务。 */
  def load(scope: AccountScope): F[Json] = withDatabase(write = false)(read(_, scope))

  /** 串行归并并原子保存消息、会话和游标；解析、写入或提交失败时回滚。 */
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json] = withDatabase(write = true) {
    connection =>
      val state = LocalCache.applyPage(read(connection, scope), page, reset)
      Using.resource(
        connection.prepareStatement(
          "INSERT INTO im_cache(scope,data) VALUES(?,?) ON CONFLICT(scope) DO UPDATE SET data=excluded.data"
        )
      ) { statement =>
        statement.setString(1, scope.key)
        statement.setString(2, state.noSpaces)
        statement.executeUpdate()
      }
      state
  }

  /** 只清除指定作用域；即使该作用域的 JSON 损坏也能清除，不影响其他账号。 */
  def clear(scope: AccountScope): F[Unit] = withDatabase(write = true) { connection =>
    Using.resource(connection.prepareStatement("DELETE FROM im_cache WHERE scope=?")) { statement =>
      statement.setString(1, scope.key)
      statement.executeUpdate()
      ()
    }
  }
}
