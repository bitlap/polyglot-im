package polyglot.im.server

import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol
import polyglot.im.server.infrastructure.postgres.PostgresStore

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite

class DirectSuite extends CatsEffectSuite {
  private def database =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(pg => IO.blocking(pg.close())).evalMap {
      pg =>
        val a = new PostgresStore[IO](pg.getPostgresDatabase)
        val b = new PostgresStore[IO](pg.getPostgresDatabase)
        a.initialize.as((a, b))
    }
  private val alice = Identity("app", "alice", "user", Long.MaxValue)
  private val bob   = alice.copy(user = "bob")
  private def command(key: String, text: String = "你好 👨‍👩‍👧‍👦👍🏽") = Json.obj(
    "client_message_id" -> Json.fromString(key),
    "type"              -> Json.fromString("chat.text"),
    "payload"           -> Json.obj("text" -> Json.fromString(text))
  )
  private def field(value: Json, name: String) = Protocol.field(value, name)

  test("两个独立 PostgresStore 双向并发：唯一会话、幂等、顺序、成员授权与双端持久同步") {
    database.use { case (a, b) =>
      for {
        pair <- (a.createDirect(alice, "bob"), b.createDirect(bob, "alice")).parTupled
        id = field(pair._1, "conversation_id")
        _  = assertEquals(field(pair._2, "conversation_id"), id)
        _ <- (1 to 30).toList.parTraverse_(i =>
          if (i % 2 == 0) a.sendDirect(alice, id, command(s"key-$i")) else b.sendDirect(bob, id, command(s"key-$i"))
        )
        retry <- List.fill(10)(b.sendDirect(alice, id, command("key-2"))).parSequence
        _ = assertEquals(retry.map(_._1.id).distinct.size, 1)
        _ = assert(retry.forall(!_._2))
        conflict <- a.sendDirect(alice, id, command("key-2", "不同内容")).attempt
        _ = assertEquals(conflict.swap.toOption.get.asInstanceOf[ApiError].status, 409)
        history <- b.directHistory(bob, id, Long.MaxValue, 100)
        rows = history.hcursor.get[List[Json]]("items").toOption.get
        _    = assertEquals(rows.map(m => field(m, "conversation_seq").toLong), (30L to 1L by -1).toList)
        _    = assertEquals(field(history.hcursor.downField("conversation").focus.get, "unread_count"), "15")
        _ = assert(rows.forall(m => Set("alice", "bob")(field(m.hcursor.downField("payload").focus.get, "sender_id"))))
        foreign  <- a.directHistory(alice.copy(user = "mallory"), id, Long.MaxValue, 10).attempt
        crossApp <- a.directHistory(alice.copy(app = "other"), id, Long.MaxValue, 10).attempt
        _ = assertEquals(foreign.swap.toOption.get.asInstanceOf[ApiError].status, 404)
        _ = assert(crossApp.isLeft)
        own <- a.get(alice, field(rows.head, "message_id"))
        _ = assertEquals(own.json, rows.head)
        hidden <- a.get(alice.copy(user = "mallory"), own.id).attempt
        _ = assert(hidden.isLeft)
        unreadState <- a.position(alice)
        _ = assertEquals(unreadState.directUnread, 15L)
        eventsA <- a.sync(alice, 0, 100)
        eventsB <- b.sync(bob, 0, 100)
        _ = assertEquals(eventsA._2.size, 31)
        _ = assertEquals(eventsB._2.size, 31)
        _ = assertEquals(eventsA._1.seq, 0L) // notification sequence is independent
        _     <- (a.readDirect(alice, id, 30), b.readDirect(bob, id, 30)).parTupled
        stale <- a.readDirect(alice, id, 1)
        _ = assertEquals(field(stale, "read_seq"), "30")
        _ = assertEquals(field(stale, "peer_read_seq"), "30")
        _ = assertEquals(field(stale, "unread_count"), "0")
        readState <- b.position(alice)
        _ = assertEquals(readState.directUnread, 0L)
        ahead <- a.readDirect(alice, id, 31).attempt
        _ = assert(ahead.isLeft)
        page <- a.directHistory(alice, id, 26, 5)
        _ = assertEquals(
          page.hcursor.get[List[Json]]("items").toOption.get.map(m => field(m, "conversation_seq")),
          List("25", "24", "23", "22", "21")
        )
        _        <- a.purgeEvents(Protocol.now + 60)
        snapshot <- b.snapshotPage(bob)
        _ = assertEquals(field(snapshot._3.head, "latest_seq"), "30")
        _ = assertEquals(snapshot._3.head.hcursor.get[List[Json]]("messages").toOption.get, Nil)
      } yield ()
    }
  }

  test("图片与伪造发送者被拒绝；单聊消息复用可靠推送且已读抑制") {
    database.use { case (a, b) =>
      for {
        _            <- a.register(bob, "ios")
        _            <- a.device(bob, "ios", Some("a" * 64), false, false)
        conversation <- a.createDirect(alice, "bob")
        id = field(conversation, "conversation_id")
        image <- a
          .sendDirect(alice, id, command("image").deepMerge(Json.obj("type" -> Json.fromString("chat.image"))))
          .attempt
        forged <- a
          .sendDirect(
            alice,
            id,
            command("forged").deepMerge(Json.obj("payload" -> Json.obj("sender_id" -> Json.fromString("bob"))))
          )
          .attempt
        empty <- a.sendDirect(alice, id, command("empty", "  ")).attempt
        _ = assert(image.isLeft && forged.isLeft && empty.isLeft)
        sent <- a.sendDirect(alice, id, command("push"))
        _    <- IO.sleep(scala.concurrent.duration.DurationInt(4).seconds)
        jobs <- b.claim
        _ = assertEquals(jobs.size, 1)
        target <- b.pushTarget(jobs.head)
        _ = assertEquals(target.get.message.json, sent._1.json)
        _          <- a.readDirect(bob, id, 1)
        suppressed <- b.pushTarget(jobs.head)
        _ = assertEquals(suppressed, None)
      } yield ()
    }
  }
}
