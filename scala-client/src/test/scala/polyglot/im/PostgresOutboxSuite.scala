package polyglot.im

import java.sql.Connection
import javax.sql.DataSource
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite

/** 真实临时数据库验证接口拆分后的事务、并发领取与旧租约围栏。 */
class PostgresOutboxSuite extends CatsEffectSuite {
  private def database =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
  private def connection(source: DataSource) =
    Resource.make(IO.blocking(source.getConnection))(c => IO.blocking(c.close()))
  private def sql(c: Connection, value: String): IO[Unit] = IO.blocking {
    val statement = c.createStatement()
    try { statement.execute(value); () }
    finally statement.close()
  }
  private def event(id: String) = NotificationIntent(id, "alice", "system.notice", Json.obj())
  private def enqueue(source: DataSource, outbox: TransactionalOutbox[IO, Connection], id: String): IO[Unit] =
    connection(source).use { c =>
      IO.blocking(c.setAutoCommit(false)) *> outbox.enqueue(c, event(id)) *> IO.blocking(c.commit())
    }

  test("业务回滚不入队，稳定 ID 拒绝内容变化，多个适配器并发领取不重复") {
    database.use { pg =>
      val source = pg.getPostgresDatabase
      val box    = new PostgresOutbox[IO](source)
      for {
        _ <- box.initialize
        _ <- connection(source).use { c =>
          for {
            invalid <- box.enqueue(c, event("no-transaction")).attempt
            _ = assert(invalid.isLeft)
            _ <- IO.blocking(c.setAutoCommit(false))
            _ <- box.enqueue(c, event("rollback"))
            _ <- IO.blocking(c.rollback())
          } yield ()
        }
        empty <- box.claim(120)
        _ = assertEquals(empty, None)
        _ <- enqueue(source, box, "same")
        _ <- enqueue(source, box, "same")
        _ <- connection(source).use { c =>
          for {
            _        <- IO.blocking(c.setAutoCommit(false))
            conflict <- box.enqueue(c, event("same").copy(recipient = "bob")).attempt
            _ = assert(conflict.isLeft)
            _ <- IO.blocking(c.rollback())
          } yield ()
        }
        _      <- (1 to 15).toList.parTraverse_(i => enqueue(source, box, s"event-$i"))
        claims <- List.fill(32)(new PostgresOutbox[IO](source).claim(120)).parSequence
        rows = claims.flatten
        _    = assertEquals(rows.size, 16)
        _    = assertEquals(rows.map(_.event.eventId).distinct.size, 16)
        _    <- rows.traverse_(row => box.complete(row, OutboxCompletion(OutboxState.Sent, 1, None)))
        done <- box.claim(120)
        _ = assertEquals(done, None)
      } yield ()
    }
  }
  test("未确认领取保留租约，过期后重领的围栏拒绝旧确认，重试仅按数据库时钟到期领取") {
    database.use { pg =>
      val source = pg.getPostgresDatabase
      val box    = new PostgresOutbox[IO](source)
      for {
        _     <- box.initialize *> enqueue(source, box, "event")
        first <- box.claim(120).map(_.get)
        busy  <- box.claim(120)
        _ = assertEquals(busy, None)
        _      <- connection(source).use(c => sql(c, "UPDATE snap_flow.im_producer_outbox SET lease_until=0"))
        second <- box.claim(120).map(_.get)
        _ = assertNotEquals(first.lease, second.lease)
        stale <- box.complete(first, OutboxCompletion(OutboxState.Sent, 1, None))
        _ = assert(!stale)
        confirmed <- box.complete(second, OutboxCompletion(OutboxState.Pending, 300, Some("NETWORK_ERROR")))
        _ = assert(confirmed)
        delayed <- box.claim(120)
        _ = assertEquals(delayed, None)
        _     <- connection(source).use(c => sql(c, "UPDATE snap_flow.im_producer_outbox SET next_at=0"))
        retry <- box.claim(120).map(_.get)
        _ = assertEquals(retry.attempts, 1)
        _ = assertEquals(retry.event.eventId, first.event.eventId)
        _         <- box.complete(retry, OutboxCompletion(OutboxState.Sent, 1, None))
        duplicate <- box.complete(retry, OutboxCompletion(OutboxState.Dead, 1, Some("STALE")))
        _ = assert(!duplicate)
      } yield ()
    }
  }
}
