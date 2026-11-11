package polyglot.im.server

import java.sql.Connection
import scala.concurrent.duration.*
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite

class DistributedStoreSuite extends CatsEffectSuite {
  override def munitIOTimeout = 90.seconds
  private val user            = Identity("cluster", "alice", "user", Long.MaxValue)
  private val service         = user.copy(user = "producer", role = "service", types = Set("*"), recipients = Set("*"))
  private def message(key: String, recipient: String = "alice") =
    Notification(recipient, key, "system.notice", Json.obj("text" -> Json.fromString(key)))
  private def database = for {
    pg <- Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
    config = DatabaseConfig(s"jdbc:postgresql://127.0.0.1:${pg.getPort}/postgres", "postgres", "", 4)
    a <- Store.resource[IO](config)
    b <- Store.resource[IO](config)
    _ <- Resource.eval((a.migrate, b.migrate).parTupled)
  } yield (pg, a, b)
  private def sql(pg: EmbeddedPostgres, command: String): IO[Unit] = IO.blocking {
    val c = pg.getPostgresDatabase.getConnection
    try {
      val s = c.createStatement();
      try { s.execute(command); () }
      finally s.close()
    } finally c.close()
  }
  private def scalar(pg: EmbeddedPostgres, command: String): IO[Long] = IO.blocking {
    val c = pg.getPostgresDatabase.getConnection
    try {
      val s = c.createStatement();
      try {
        val r = s.executeQuery(command);
        try { r.next(); r.getLong(1) }
        finally r.close()
      } finally s.close()
    } finally c.close()
  }
  test("两个独立连接池：并发迁移、跨实例幂等、连续序号和单调阅读水位") {
    database.use { case (_, a, b) =>
      for {
        duplicates <- (0 until 32).toList.parTraverse(i => (if (i % 2 == 0) a else b).send(service, message("same")))
        _ = assertEquals(duplicates.map(_._1.id).distinct.size, 1)
        _ = assertEquals(duplicates.count(_._2), 1)
        _ <- (0 until 64).toList.parTraverse_(i => (if (i % 2 == 0) a else b).send(service, message(s"unique-$i")))
        conflict <- b.send(service, message("same", "bob")).attempt
        _ = assertEquals(conflict.swap.toOption.get.asInstanceOf[ApiError].code, "IDEMPOTENCY_CONFLICT")
        page <- b.sync(user, 0, 100)
        _ = assertEquals(page._2.map(_._1), (1L to 65L).toList)
        _   <- (0L to 65L).toList.parTraverse_(n => (if (n % 2 == 0) a else b).read(user, n))
        pos <- a.position(user)
        _ = assertEquals(pos.read, 65L)
        _ = assertEquals(pos.unread, 0L)
        snapshot <- b.snapshot(user)
        _ = assertEquals(snapshot._2.map(_.seq).max, snapshot._1.seq)
      } yield ()
    }
  }
  test("读取使用只读一致性快照，不等待用户写锁，不为不存在的账户插入状态") {
    database.use { case (pg, a, b) =>
      for {
        _ <- a.send(service, message("before-lock"))
        _ <- Resource
          .make(IO.blocking {
            val c = pg.getPostgresDatabase.getConnection
            c.setAutoCommit(false)
            val s = c.createStatement()
            try s.execute("UPDATE im_user_state SET message_seq=999 WHERE app_id='cluster' AND user_id='alice'")
            finally s.close()
            c
          })(c => IO.blocking { c.rollback(); c.close() })
          .use { _ =>
            (for {
              snapshot <- b.snapshot(user)
              events   <- b.sync(user, 0, 100)
              _ = assertEquals(snapshot._1.seq, 1L)
              _ = assertEquals(events._1.seq, 1L)
              _ = assertEquals(events._2.size, 1)
            } yield ()).timeout(2.seconds)
          }
        empty <- a.position(user.copy(user = "missing"))
        _ = assertEquals(empty, Position(0, 0, 0, 0))
        rows <- scalar(pg, "SELECT count(*) FROM im_user_state WHERE user_id='missing'")
        _ = assertEquals(rows, 0L)
      } yield ()
    }
  }
  test("写入和快照/同步并发时，同一响应的水位与事件来自相同快照") {
    database.use { case (_, a, b) =>
      val writes = (0 until 64).toList.traverse_(i => a.send(service, message(s"snapshot-$i")))
      val reads = (0 until 100).toList.traverse_ { _ =>
        for {
          snapshot <- b.snapshot(user)
          _ = assertEquals(snapshot._2.headOption.fold(0L)(_.seq), snapshot._1.seq)
          page <- b.sync(user, 0, 100)
          _ = assert(page._2.forall(_._1 <= page._1.cursor))
          _ = assertEquals(page._2.size.toLong, page._1.cursor)
        } yield ()
      }
      (writes, reads).parTupled.void
    }
  }
  test("有界维护不删除并发刷新后的限流计数，不重置正在使用的配额") {
    database.use { case (pg, a, b) =>
      for {
        _ <- a.allow(user, 1)
        _ <- sql(pg, "UPDATE im_rate_limit SET window_start=0")
        _ <- Resource
          .make(IO.blocking {
            val c = pg.getPostgresDatabase.getConnection
            c.setAutoCommit(false)
            val s = c.createStatement()
            try
              s.execute(
                "UPDATE im_rate_limit SET window_start=floor(extract(epoch FROM clock_timestamp())/60)::bigint,hits=1"
              )
            finally s.close()
            c
          })(c => IO.blocking { c.rollback(); c.close() })
          .use { c =>
            for {
              cleaning <- b.maintain.start
              _        <- IO.sleep(150.millis)
              _        <- IO.blocking(c.commit())
              _        <- cleaning.joinWithNever.timeout(3.seconds)
            } yield ()
          }
        rows <- scalar(pg, "SELECT count(*) FROM im_rate_limit")
        _ = assertEquals(rows, 1L)
        permitted <- a.allow(user, 1)
        _ = assert(!permitted)
      } yield ()
    }
  }
  test("分布式限流原子计数，增加实例不增加配额，应用命名空间隔离") {
    database.use { case (_, a, b) =>
      for {
        accepted <- (0 until 48).toList.parTraverse(i => (if (i % 2 == 0) a else b).allow(user, 10))
        _ = assertEquals(accepted.count(identity), 10)
        other <- a.allow(user.copy(app = "other"), 10)
        _ = assert(other)
        limited <- b.allow(user, 10)
        _ = assert(!limited)
      } yield ()
    }
  }
  test("并发领取无重叠；租约接管后旧 Worker 不能完成任务或清除新 token；重试预算进入死信") {
    database.use { case (pg, a, b) =>
      for {
        _    <- a.register(user, "ios")
        _    <- a.device(user, "ios", Some("a" * 64), false, false)
        _    <- a.send(service, message("push-1"))
        _    <- b.send(service, message("push-2"))
        _    <- sql(pg, "UPDATE im_outbox SET next_at=0")
        pair <- (a.claimBatch(1, 60), b.claimBatch(1, 60)).parTupled
        old   = pair._1.head
        other = pair._2.head
        _     = assertNotEquals(old.id, other.id)
        _           <- b.finish(other, PushResult(false), None)
        _           <- sql(pg, s"UPDATE im_outbox SET lease_until=0 WHERE id='${old.id}'")
        replacement <- b.claimBatch(1, 60).map(_.head)
        _ = assertEquals(replacement.id, old.id)
        _ = assertNotEquals(replacement.claimToken, old.claimToken)
        _      <- a.finish(old, PushResult(false, true), Some("a" * 64))
        target <- b.pushTarget(replacement)
        _ = assert(target.nonEmpty)
        pending <- scalar(pg, "SELECT count(*) FROM im_outbox WHERE state='pending'")
        _ = assertEquals(pending, 1L)
        _    <- b.finish(replacement, PushResult(false), None)
        _    <- a.send(service, message("push-dead"))
        _    <- sql(pg, "UPDATE im_outbox SET next_at=0,attempts=9 WHERE state='pending'")
        last <- a.claimBatch(1, 60).map(_.head)
        _ = assertEquals(last.attempts, 10)
        _    <- a.finish(last, PushResult(true), None)
        dead <- scalar(pg, "SELECT count(*) FROM im_outbox WHERE state='dead'")
        _ = assertEquals(dead, 1L)
        remaining <- b.claim
        _ = assertEquals(remaining, Nil)
      } yield ()
    }
  }
  test("两个真实 Worker 有界并行，只领取立即执行的任务，完成后继续排空") {
    database.use { case (pg, a, b) =>
      for {
        _       <- a.register(user, "worker-ios")
        _       <- a.device(user, "worker-ios", Some("a" * 64), false, false)
        _       <- (0 until 5).toList.traverse_(i => a.send(service, message(s"worker-$i")))
        _       <- sql(pg, "UPDATE im_outbox SET next_at=0")
        active  <- Ref.of[IO, Int](0)
        peak    <- Ref.of[IO, Int](0)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        provider = new PushProvider[IO] {
          def send(target: PushTarget): IO[PushResult] = Resource
            .make(
              active.updateAndGet(_ + 1).flatTap { n =>
                peak.update(_.max(n)) *> (if (n == 4) entered.complete(()).void else IO.unit)
              }
            )(_ => active.update(_ - 1))
            .use(_ => release.get.as(PushResult(false)))
        }
        settings = PushWorkerSettings(2, 10.seconds, 5.seconds, 50.millis)
        _ <- (for {
          _ <- Resource.make(PushWorker.run(a, provider, settings).start)(_.cancel)
          _ <- Resource.make(PushWorker.run(b, provider, settings).start)(_.cancel)
        } yield ()).use { _ =>
          for {
            _       <- entered.get.timeout(4.seconds)
            claimed <- scalar(pg, "SELECT count(*) FROM im_outbox WHERE attempts>0")
            _ = assertEquals(claimed, 4L)
            _ <- release.complete(())
            _ <- scalar(pg, "SELECT count(*) FROM im_outbox WHERE state='done'")
              .flatTap(_ => IO.sleep(20.millis))
              .iterateUntil(_ == 5L)
              .timeout(5.seconds)
            maximum <- peak.get
            _ = assertEquals(maximum, 4)
            attempts <- scalar(pg, "SELECT max(attempts) FROM im_outbox")
            _ = assertEquals(attempts, 1L)
          } yield ()
        }
      } yield ()
    }
  }
  test("跨实例 token 唯一归属、票据单次消费和登出后禁止签发") {
    database.use { case (pg, a, b) =>
      for {
        _ <- (0 until 8).toList.traverse_(i => a.register(user.copy(user = s"user-$i"), "ios"))
        _ <- (0 until 8).toList.parTraverse_(i =>
          (if (i % 2 == 0) a else b).device(user.copy(user = s"user-$i"), "ios", Some("b" * 64), false, false)
        )
        owners <- scalar(pg, "SELECT count(*) FROM im_device WHERE token IS NOT NULL")
        _ = assertEquals(owners, 1L)
        _        <- a.register(user, "ios")
        ticket   <- a.ticket(user, "ios")
        consumed <- List(a, b).parTraverse(_.consumeTicket(ticket).attempt)
        _ = assertEquals(consumed.count(_.isRight), 1)
        _    <- b.logout(user, "ios")
        late <- a.ticket(user, "ios").attempt
        _ = assert(late.isLeft)
      } yield ()
    }
  }
  test("提交后跨连接唤醒，监听断线自动重连；无通知时五秒补拉仍恢复") {
    database.use { case (pg, a, _) =>
      val resources = for {
        connections <- Resource.eval(Ref.of[IO, List[Connection]](Nil))
        feed <- ChangeFeed.resource[IO](
          IO.blocking(pg.getPostgresDatabase.getConnection).flatTap(c => connections.update(c :: _))
        )
        _ <- Resource.eval(feed.connected.flatTap(_ => IO.sleep(20.millis)).iterateUntil(identity).timeout(5.seconds))
      } yield (feed, connections)
      resources.use { case (feed, connections) =>
        feed.subscribe(user).use { ticks =>
          for {
            received <- ticks.drop(1).take(1).compile.drain.start
            _        <- a.send(service, message("wake"))
            _        <- received.joinWithNever.timeout(2.seconds)
            _        <- connections.get.flatMap(list => IO.blocking(list.head.close()))
            _        <- IO.sleep(1500.millis)
            _        <- feed.connected.flatTap(_ => IO.sleep(20.millis)).iterateUntil(identity).timeout(5.seconds)
            count    <- connections.get.map(_.size)
            _ = assert(count >= 2)
            // 完全没有监听连接时，仍会收到五秒协调信号。
            _ <- ChangeFeed.resource[IO](IO.raiseError[Connection](new java.sql.SQLException("offline test"))).use {
              offline =>
                offline.subscribe(user).use(_.drop(1).take(1).compile.drain.timeout(6.seconds))
            }
          } yield ()
        }
      }
    }
  }
}
