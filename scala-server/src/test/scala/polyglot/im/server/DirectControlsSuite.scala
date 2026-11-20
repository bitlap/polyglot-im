package polyglot.im.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite
import polyglot.im.server.config.DirectChatSettings
import polyglot.im.server.domain.{ApiError, Identity, PeerRelationship, Protocol}
import polyglot.im.server.infrastructure.postgres.PostgresRepository

/** 使用自有临时 PostgreSQL 核对权限、个人墓碑、阅读计数、在线隐私及派发时复核。 */
class DirectControlsSuite extends CatsEffectSuite {
  private val alice    = Identity("app", "alice", "user", Long.MaxValue)
  private val bob      = alice.copy(user = "bob")
  private val mutual   = Some(PeerRelationship(true, true, true))
  private val stranger = Some(PeerRelationship(true, false, false))
  private def db: Resource[IO, (EmbeddedPostgres, PostgresRepository[IO], PostgresRepository[IO])] =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().start()))(p => IO.blocking(p.close())).evalMap { pg =>
      val settings = DirectChatSettings(true, Some("http://127.0.0.1/relationships"))
      val a        = new PostgresRepository[IO](pg.getPostgresDatabase, settings);
      val b        = new PostgresRepository[IO](pg.getPostgresDatabase, settings)
      a.initialize.as((pg, a, b))
    }
  private def field(j: Json, k: String) = Protocol.field(j, k)
  private def command(key: String, text: String = "你好") = Json.obj(
    "client_message_id" -> Json.fromString(key),
    "type"              -> Json.fromString("chat.text"),
    "payload"           -> Json.obj("text" -> Json.fromString(text))
  )
  private def settings(scope: String, online: String = "mutual") =
    Json.obj("inbound_scope" -> Json.fromString(scope), "online_visibility" -> Json.fromString(online))
  private def sql(pg: EmbeddedPostgres, value: String): IO[Unit] = IO.blocking {
    val c = pg.getPostgresDatabase.getConnection;
    try {
      val s = c.createStatement();
      try { s.execute(value); () }
      finally s.close()
    } finally c.close()
  }
  private def denied(result: Either[Throwable, ?]): Unit = {
    assert(result.isLeft); assertEquals(result.swap.toOption.get.asInstanceOf[ApiError].status, 403)
  }

  test("已启用数据库不能关闭控制退回放行，缺表也不就绪") {
    db.use { case (pg, a, _) =>
      val disabled = new PostgresRepository[IO](pg.getPostgresDatabase)
      for {
        denied <- disabled.initialize.attempt
        _ = assertEquals(denied.swap.toOption.get.asInstanceOf[ApiError].status, 503)
        _     <- sql(pg, "DROP TABLE snap_flow.im_presence")
        ready <- a.ready
        _ = assertEquals(ready, false)
        startup <- a.initialize.attempt
        _ = assertEquals(startup.swap.toOption.get.asInstanceOf[ApiError].status, 503)
      } yield ()
    }
  }

  test("陌生人范围、账号存在性与拉黑在创建和每次发送重试时执行") {
    db.use { case (_, a, b) =>
      for {
        initial <- a.createDirect(alice, "bob", stranger).attempt
        _ = denied(initial)
        _    <- a.updateDirectSettings(bob, settings("all"))
        chat <- a.createDirect(alice, "bob", stranger)
        id = field(chat, "conversation_id")
        sent  <- a.sendDirect(alice, id, command("key"), stranger)
        _     <- b.updatePeerPreferences(bob, "alice", Json.obj("blocked" -> Json.True))
        retry <- a.sendDirect(alice, id, command("key"), stranger).attempt
        _ = denied(retry)
        next <- a.sendDirect(alice, id, command("next"), mutual).attempt
        _ = denied(next)
        create <- a.createDirect(alice, "bob", mutual).attempt
        _ = denied(create)
        prefs <- a.peerPreferences(alice, "bob", mutual.get)
        _ = assertEquals(prefs.hcursor.get[Boolean]("can_send").toOption, Some(false))
        _ = assertEquals(prefs.hcursor.get[Boolean]("blocked").toOption, Some(false))
        missing <- a.createDirect(alice, "missing", Some(PeerRelationship(false, true, true))).attempt
        _ = denied(missing)
        historical <- a.get(bob, sent._1.id)
        _ = assertEquals(field(historical.payload, "text"), "你好")
      } yield ()
    }
  }
  test("撤回保留双方墓碑，拒绝他人和超时操作，清空及删除不会产生负未读") {
    db.use { case (pg, a, b) =>
      for {
        chat <- a.createDirect(alice, "bob", mutual)
        id = field(chat, "conversation_id")
        sent    <- a.sendDirect(alice, id, command("key"), mutual)
        foreign <- b.updateDirectMessage(bob, id, sent._1.id, true).attempt
        _ = denied(foreign)
        _        <- a.updateDirectMessage(alice, id, sent._1.id, true)
        _        <- a.updateDirectMessage(alice, id, sent._1.id, true)
        recalled <- b.get(bob, sent._1.id)
        _ = assertEquals(recalled.kind, "chat.recalled")
        _ = assert(!recalled.payload.hcursor.downField("text").succeeded)
        count <- b.position(bob)
        _ = assertEquals(count.directUnread, 0L)
        _       <- b.readDirect(bob, id, 1)
        next    <- a.sendDirect(alice, id, command("next"), mutual)
        _       <- b.updateDirectMessage(bob, id, next._1.id, false)
        deleted <- b.get(bob, next._1.id).attempt
        _ = assert(deleted.isLeft)
        retained <- a.get(alice, next._1.id)
        _ = assertEquals(retained.kind, "chat.text")
        _       <- b.readDirect(bob, id, 2)
        _       <- b.updateDirectVisibility(bob, id, 2, true, true)
        _       <- a.sendDirect(alice, id, command("newer"), mutual)
        history <- b.directHistory(bob, id, Long.MaxValue, 50)
        _ = assertEquals(history.hcursor.downField("conversation").get[Boolean]("hidden").toOption, Some(false))
        _ = assertEquals(field(history.hcursor.downField("conversation").focus.get, "clear_seq"), "2")
        state <- b.position(bob)
        _ = assertEquals(state.directUnread, 1L)
        old <- a.sendDirect(alice, id, command("old"), mutual)
        _ <- sql(pg, "UPDATE snap_flow.im_direct_message SET created_at='2000-01-01T00:00:00Z' WHERE client_key='old'")
        expired <- a.updateDirectMessage(alice, id, old._1.id, true).attempt
        _ = assertEquals(expired.swap.toOption.get.asInstanceOf[ApiError].code, ApiError.Code.RecallExpired)
        _   <- b.readDirect(bob, id, 4)
        end <- b.position(bob)
        _ = assertEquals(end.directUnread, 0L)
      } yield ()
    }
  }
  test("在线状态依赖活跃设备租约、双方开启、互关且无拉黑") {
    db.use { case (pg, a, b) =>
      for {
        _      <- a.register(bob, "ios")
        _      <- a.updatePresence(bob, "ios", true)
        online <- a.presence(alice, Map("bob" -> mutual.get))
        _ = assertEquals(online.hcursor.downField("items").downArray.get[String]("state").toOption, Some("online"))
        _      <- b.updateDirectSettings(bob, settings("mutual", "off"))
        hidden <- a.presence(alice, Map("bob" -> mutual.get))
        _ = assertEquals(hidden.hcursor.downField("items").downArray.get[String]("state").toOption, Some("hidden"))
        _       <- b.updateDirectSettings(bob, settings("mutual"))
        _       <- b.logout(bob, "ios")
        offline <- a.presence(alice, Map("bob" -> mutual.get))
        _ = assertEquals(offline.hcursor.downField("items").downArray.get[String]("state").toOption, Some("offline"))
        _       <- a.updatePeerPreferences(alice, "bob", Json.obj("blocked" -> Json.True))
        blocked <- a.presence(alice, Map("bob" -> mutual.get))
        _ = assertEquals(blocked.hcursor.downField("items").downArray.get[String]("state").toOption, Some("hidden"))
        revoked <- b.updatePresence(bob, "ios", true).attempt
        _ = assert(revoked.isLeft)
      } yield ()
    }
  }
  test("已入队的私信在投递前重查免打扰、拉黑和撤回") {
    db.use { case (pg, a, b) =>
      for {
        _    <- b.register(bob, "ios")
        _    <- b.device(bob, "ios", Some("a" * 64), false, true)
        chat <- a.createDirect(alice, "bob", mutual)
        id = field(chat, "conversation_id")
        sent <- a.sendDirect(alice, id, command("push"), mutual)
        _    <- sql(pg, "UPDATE snap_flow.im_outbox SET next_at=0")
        jobs <- a.claim
        _ = assertEquals(jobs.size, 1)
        target <- a.pushTarget(jobs.head)
        _ = assert(target.nonEmpty)
        _     <- b.updatePeerPreferences(bob, "alice", Json.obj("muted" -> Json.True))
        muted <- a.pushTarget(jobs.head)
        _ = assert(muted.isEmpty)
        _       <- b.updatePeerPreferences(bob, "alice", Json.obj("muted" -> Json.False, "blocked" -> Json.True))
        blocked <- a.pushTarget(jobs.head)
        _ = assert(blocked.isEmpty)
        _        <- b.updatePeerPreferences(bob, "alice", Json.obj("blocked" -> Json.False))
        _        <- a.updateDirectMessage(alice, id, sent._1.id, true)
        recalled <- a.pushTarget(jobs.head)
        _ = assert(recalled.isEmpty)
      } yield ()
    }
  }
}
