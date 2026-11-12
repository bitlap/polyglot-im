package polyglot.im.infrastructure.cache

import java.sql.Connection
import javax.sql.DataSource
import scala.util.Using
import scala.util.control.NonFatal
import cats.effect.Async
import io.circe.Json
import io.circe.parser.parse
import polyglot.im.domain.{AccountScope, LocalCache}
import polyglot.im.ports.LocalStore

/** PostgreSQL 客户端缓存，借用宿主 DataSource；不创建或关闭宿主连接池。 */
final class PostgresStore[F[_]: Async](
    source: DataSource,
    namespace: String,
    schema: String = "snap_flow",
    lockTimeoutMillis: Int = 15000
) extends LocalStore[F] {
  require(namespace.trim.nonEmpty && namespace.length <= 200, "namespace must contain 1..200 characters")
  require(schema.matches("[a-z_][a-z0-9_]{0,62}"), "schema must be a PostgreSQL identifier")
  require(lockTimeoutMillis > 0, "lockTimeoutMillis must be positive")
  private val table = s"$schema.im_client_cache"

  private def execute(c: Connection, sql: String, values: String*): Unit =
    Using.resource(c.prepareStatement(sql)) { statement =>
      values.zipWithIndex.foreach { case (value, index) => statement.setString(index + 1, value) }
      statement.execute(); ()
    }

  private def transaction[A](run: Connection => A): F[A] = Async[F].blocking {
    Using.resource(source.getConnection) { c =>
      c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      c.setAutoCommit(false)
      try {
        execute(c, "SELECT set_config('lock_timeout', ?, true)", s"${lockTimeoutMillis}ms")
        val value = run(c)
        c.commit()
        value
      } catch {
        case NonFatal(error) =>
          try c.rollback()
          catch { case NonFatal(rollback) => error.addSuppressed(rollback) }
          throw error
      }
    }
  }

  /** 显式建表，初始化事务使用跨实例 DDL 锁；不触碰业务 Outbox 表。 */
  def initialize: F[Unit] = transaction { c =>
    execute(c, "SELECT pg_advisory_xact_lock(674239012)")
    execute(c, s"CREATE SCHEMA IF NOT EXISTS $schema")
    execute(
      c,
      s"CREATE TABLE IF NOT EXISTS $table(namespace TEXT NOT NULL,scope TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(namespace,scope))"
    )
  }

  private def lock(c: Connection, scope: AccountScope): Unit =
    execute(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,674239012))", namespace + "\n" + scope.key)

  private def read(c: Connection, scope: AccountScope): Json =
    Using.resource(c.prepareStatement(s"SELECT data FROM $table WHERE namespace=? AND scope=?")) { statement =>
      statement.setString(1, namespace)
      statement.setString(2, scope.key)
      Using.resource(statement.executeQuery()) { rows =>
        if (!rows.next()) LocalCache.empty
        else parse(rows.getString(1)).fold(error => throw new IllegalStateException("corrupt cache", error), identity)
      }
    }

  /** 不持有写锁读取已提交缓存，作用域无数据时返回空缓存，并归还连接。 */
  def load(scope: AccountScope): F[Json] = Async[F].blocking {
    Using.resource(source.getConnection)(read(_, scope))
  }

  /** 跨实例串行归并并原子提交消息、会话及游标；失败回滚，reset 使用快照重建。 */
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json] = transaction { c =>
    lock(c, scope)
    val value = LocalCache.applyPage(read(c, scope), page, reset)
    execute(
      c,
      s"INSERT INTO $table(namespace,scope,data) VALUES(?,?,?) ON CONFLICT(namespace,scope) DO UPDATE SET data=excluded.data",
      namespace,
      scope.key,
      value.noSpaces
    )
    value
  }

  /** 与提交共用作用域锁，只删除本 namespace / scope；损坏 JSON 也可清理。 */
  def clear(scope: AccountScope): F[Unit] = transaction { c =>
    lock(c, scope)
    execute(c, s"DELETE FROM $table WHERE namespace=? AND scope=?", namespace, scope.key)
  }
}
