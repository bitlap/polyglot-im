package polyglot.im.server

import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Notification
import polyglot.im.server.domain.Protocol
import polyglot.im.server.domain.PushResult
import polyglot.im.server.infrastructure.postgres.PostgresStore

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite

class StoreSuite extends CatsEffectSuite {
  private def database =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(pg => IO.blocking(pg.close())).evalMap {
      pg =>
        val store = new PostgresStore[IO](pg.getPostgresDatabase)
        store.initialize.as(store)
    }
  private val user    = Identity("test-app", "alice", "user", Long.MaxValue)
  private val service = Identity("test-app", "task-service", "service", Long.MaxValue, Set("*"), Set("*"))
  private def notification(key: String, user: String = "alice") = Notification(
    user,
    key,
    "task.completed",
    Json.obj("task_id" -> Json.fromString("task-1"), "text" -> Json.fromString("完成"))
  )

  test("真实 PostgreSQL：事务、幂等、并发顺序、阅读水位、设备、Outbox、游标过期") {
    database.use { store =>
      for {
        _     <- store.register(user, "ios-a")
        _     <- store.device(user, "ios-a", Some("a" * 64), false, false)
        _     <- store.device(user, "ios-a", Some("a" * 64), true, true)
        _     <- store.patchDevice(user, "ios-a", Some("b" * 64), None, None)
        prefs <- store.preferences(user, "ios-a")
        _ = assertEquals(prefs.hcursor.get[Boolean]("muted").toOption, Some(true))
        _         <- store.device(user, "ios-a", Some("a" * 64), false, false)
        first     <- store.send(service, notification("same"))
        duplicate <- store.send(service, notification("same"))
        conflict  <- store.send(service, notification("same", "bob")).attempt
        _ = assertEquals(first._1.id, duplicate._1.id)
        _ = assert(!duplicate._2)
        _ = assert(conflict.swap.toOption.get.isInstanceOf[ApiError])
        _   <- (1 to 20).toList.parTraverse_(i => store.send(service, notification(s"parallel-$i")))
        pos <- store.position(user)
        _ = assertEquals(pos.seq, 21L)
        all <- store.sync(user, 0, 100)
        _ = assertEquals(all._2.map(_._1), (1L to 21L).toList)
        read <- store.read(user, first._1.seq)
        _ = assertEquals(read.unread, 20L)
        _     <- store.read(user, 0)
        still <- store.position(user)
        _ = assertEquals(still.read, 1L)
        page <- store.history(user, Long.MaxValue, 5)
        _ = assertEquals(page._1.map(_.seq), List(21L, 20L, 19L, 18L, 17L))
        foreign <- store.get(user.copy(user = "bob"), first._1.id).attempt
        _ = assert(foreign.isLeft)
        ticket  <- store.ticket(user, "ios-a")
        decoded <- store.consumeTicket(ticket)
        _ = assertEquals(decoded._1.user, "alice")
        consumed <- store.consumeTicket(ticket).attempt
        _ = assert(consumed.isLeft)
        _      <- store.logout(user, "ios-a")
        active <- store.active(user, "ios-a")
        _ = assert(!active)
        late <- store.device(user, "ios-a", Some("a" * 64), false, false).attempt
        _ = assertEquals(late.swap.toOption.get.asInstanceOf[ApiError].code, "DEVICE_SESSION_CLOSED")
        _     <- store.purgeEvents(Protocol.now + 60)
        reset <- store.sync(user, 0, 10).attempt
        _ = assertEquals(reset.swap.toOption.get.asInstanceOf[ApiError].code, "RESET_REQUIRED")
        snapshot <- store.snapshot(user)
        _ = assertEquals(snapshot._2.size, 21)
        recovered <- store.sync(user, snapshot._1.cursor, 10)
        _ = assertEquals(recovered._2.size, 0)
      } yield ()
    }
  }

  test("只有事务提交过的设备推送任务可领取；已读/静音/登出抑制投递") {
    database.use { store =>
      for {
        _    <- store.register(user, "ios-a")
        _    <- store.device(user, "ios-a", Some("b" * 64), false, false)
        msg  <- store.send(service, notification("push"))
        _    <- IO.sleep(scala.concurrent.duration.DurationInt(4).seconds)
        jobs <- store.claim
        _ = assertEquals(jobs.size, 1)
        target <- store.pushTarget(jobs.head)
        _ = assert(target.nonEmpty)
        _      <- store.read(user, msg._1.seq)
        hidden <- store.pushTarget(jobs.head)
        _ = assertEquals(hidden, None)
        _    <- store.finish(jobs.head, PushResult(false), None)
        next <- store.claim
        _ = assertEquals(next, Nil)
      } yield ()
    }
  }
}
