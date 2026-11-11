package polyglot.im.server

import java.net.URI
import java.nio.file.Files
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite
import polyglot.im.{
  AccountScope,
  ApiClient,
  AtomicFileStore,
  ImClient,
  NotificationSender,
  JdbcOutbox,
  NotificationIntent
}
import polyglot.im.transport.*

class IntegrationSuite extends CatsEffectSuite {
  override def munitIOTimeout = 60.seconds
  test("真实 HTTP + WebSocket：SDK 发布、断点同步、跨设备已读和账户隔离") {
    val resources = for {
      pg <- Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
      store = new Store[IO](pg.getPostgresDatabase)
      _ <- Resource.eval(store.migrate)
      outbox = new JdbcOutbox[IO](pg.getPostgresDatabase)
      _ <- Resource.eval(outbox.initialize)
      _ <- Resource.eval(IO.blocking {
        val c = pg.getPostgresDatabase.getConnection
        try {
          c.setAutoCommit(false)
          val statement = c.createStatement()
          try {
            statement.execute("CREATE TABLE business_task(id TEXT PRIMARY KEY)"); c.commit()
            statement.execute("INSERT INTO business_task VALUES('rolled-back')")
            outbox.enqueue(c, NotificationIntent("rollback", "alice", "system.notice", Json.obj()))
            c.rollback()
            val rows = statement.executeQuery("SELECT count(*) FROM im_producer_outbox")
            try { assert(rows.next()); assertEquals(rows.getInt(1), 0) }
            finally rows.close()
            statement.execute("INSERT INTO business_task VALUES('committed')")
            outbox.enqueue(
              c,
              NotificationIntent(
                "committed",
                "alice",
                "system.notice",
                Json.obj("text" -> Json.fromString("business committed"))
              )
            )
            c.commit()
          } finally statement.close()
        } finally c.close()
      })
      changes <- ChangeFeed.resource[IO](IO.blocking(pg.getPostgresDatabase.getConnection))
      auth = new Authenticator[IO] {
        def authenticate(token: String): IO[Identity] = token match {
          case "alice" | "bob" => IO.pure(Identity("test", token, "user", Protocol.now + 3600))
          case "service" => IO.pure(Identity("test", "producer", "service", Protocol.now + 3600, Set("*"), Set("*")))
          case _         => IO.raiseError(ApiError(401, "UNAUTHORIZED", "invalid token"))
        }
      }
      api   = new Api[IO](store, auth, new CursorCodec("c" * 32), RateLimiter.postgres(store), changes = Some(changes))
      ready = new Readiness[IO] { def check: IO[Boolean] = store.ready }
      server <- Server.websocketResource[IO](
        ServerConfig(Host.fromString("127.0.0.1").get, Port.fromInt(0).get),
        ws => Server.app(ready, api.routes(ws))
      )
      base = "http://127.0.0.1:" + server.address.getPort
      network <- JdkTransport.resource[IO](URI.create(base))
      directory <- Resource.make(IO.blocking(Files.createTempDirectory("im-sdk-test")))(p =>
        IO.blocking {
          val s = Files.walk(p);
          try s.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
          finally s.close()
        }
      )
      cache = new AtomicFileStore[IO](directory)
      apiFor = (credential: String) =>
        new ApiClient[IO](
          new AuthenticatedTransport(network._1, new TokenProvider[IO] { def token: IO[String] = IO.pure(credential) })
        )
      alice <- Resource.eval(
        ImClient.create(apiFor("alice"), AccountScope(base, "test", "alice"), "ios-a", cache, network._2)
      )
      second <- Resource.eval(
        ImClient.create(apiFor("alice"), AccountScope(base, "test", "alice"), "ios-b", cache, network._2)
      )
    } yield (alice, second, new NotificationSender[IO](apiFor("service")), apiFor("bob"), outbox)
    resources.use { case (alice, second, sender, bob, outbox) =>
      for {
        _         <- alice.initialize.timeout(5.seconds)
        message   <- sender.send("alice", "task-1", "task.completed", Json.obj("task_id" -> Json.fromString("task-1")))
        duplicate <- sender.send("alice", "task-1", "task.completed", Json.obj("task_id" -> Json.fromString("task-1")))
        _ = assertEquals(message.messageId, duplicate.messageId)
        state <- alice.sync
        _ = assertEquals(state.hcursor.get[String]("unread_count").toOption, Some("1"))
        foreign <- bob.request("GET", "/messages/" + message.messageId).attempt
        _ = assert(foreign.isLeft)
        mismatched <- bob.scoped(AccountScope("unused", "test", "alice")).request("GET", "/snapshot").attempt
        _ = assertEquals(mismatched.swap.toOption.get.asInstanceOf[polyglot.im.ImError].code, "IDENTITY_MISMATCH")
        _ <- second.initialize
        _ <- second.markRead()
        updated <- alice.watch
          .filter(_.hcursor.get[String]("unread_count").contains("0"))
          .take(1)
          .compile
          .lastOrError
          .timeout(10.seconds)
        _ = assertEquals(updated.hcursor.get[String]("read_seq").toOption, Some("1"))
        direct <- alice.createDirect("bob")
        cid = Protocol.field(direct, "conversation_id")
        sent  <- alice.sendText(cid, "alice-chat", "你好 😊")
        retry <- alice.sendText(cid, "alice-chat", "你好 😊")
        _ = assertEquals(sent.messageId, retry.messageId)
        reply <- bob.request(
          "POST",
          s"/conversations/$cid/messages",
          Some(
            Json.obj(
              "client_message_id" -> Json.fromString("bob-chat"),
              "type"              -> Json.fromString("chat.text"),
              "payload"           -> Json.obj("text" -> Json.fromString("收到 👍🏽"))
            )
          )
        )
        chatState <- alice.watch
          .filter(_.hcursor.downField("conversations").downField(cid).get[String]("latest_seq").contains("2"))
          .take(1)
          .compile
          .lastOrError
          .timeout(10.seconds)
        _ = assertEquals(
          chatState.hcursor.downField("conversations").downField(cid).get[String]("unread_count").toOption,
          Some("1")
        )
        _          <- alice.markConversationRead(cid, "2")
        bobHistory <- bob.request("GET", s"/conversations/$cid/messages")
        _ = assertEquals(bobHistory.hcursor.downField("conversation").get[String]("peer_read_seq").toOption, Some("2"))
        _ = assertEquals(Protocol.field(reply.hcursor.downField("payload").focus.get, "sender_id"), "bob")
        _       <- alice.close()
        _       <- second.close()
        drained <- outbox.drainOnce(sender)
        empty   <- outbox.drainOnce(sender)
        _ = assert(drained && !empty)
      } yield ()
    }
  }
}
