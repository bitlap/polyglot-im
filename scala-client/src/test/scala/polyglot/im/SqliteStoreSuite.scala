package polyglot.im

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.concurrent.duration.*
import scala.util.Using
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite

/** 使用真实 SQLite 文件验证事务、重启恢复、命名空间和并发，不依赖外部数据库。 */
class SqliteStoreSuite extends CatsEffectSuite {
  private val scope = AccountScope("https://im.example", "app", "alice")
  private def fixture(name: String): Json =
    parse(Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol/fixtures", name))).toOption.get
  private val page = fixture("sync.json")
  private def message(id: Int): Json = fixture("message.json").deepMerge(
    Json.obj(
      "message_id"       -> Json.fromString(s"message-$id"),
      "conversation_seq" -> Json.fromString(id.toString),
      "type"             -> Json.fromString("custom.future"),
      "payload"          -> Json.obj("text" -> Json.fromString("你好 👨‍👩‍👧‍👦"), "future" -> Json.fromString("kept"))
    )
  )
  private def database: Resource[IO, Path] = Resource
    .make(IO.blocking(Files.createTempDirectory("im-sqlite")))(path =>
      IO.blocking {
        Using.resource(Files.walk(path))(_.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete))
      }
    )
    .map(_.resolve("账户 缓存.sqlite"))
  private def sql[A](path: Path)(run: Connection => A): IO[A] = IO.blocking {
    Using.resource(DriverManager.getConnection("jdbc:sqlite:" + path.toUri.toASCIIString))(run)
  }
  private def execute(path: Path, command: String): IO[Unit] = sql(path) { connection =>
    Using.resource(connection.createStatement()) { statement =>
      statement.execute(command); ()
    }
  }

  test("共享契约、大序号及账号/环境/应用隔离，重建实例恢复并只清理指定 scope") {
    database.use { path =>
      val store = new SqliteStore[IO](path)
      val others =
        List(scope.copy(userId = "bob"), scope.copy(appId = "other"), scope.copy(environment = "https://other.example"))
      for {
        saved <- store.commit(scope, page)
        _ = assertEquals(LocalCache.field(saved, "latest_seq"), "9007199254740993")
        _ = assertEquals(LocalCache.field(saved, "unread_count"), "1")
        reopened <- new SqliteStore[IO](path).load(scope)
        _ = assertEquals(reopened, saved)
        _       <- others.traverse_(other => store.load(other).map(value => assertEquals(value, LocalCache.empty)))
        _       <- store.commit(others.head, Json.obj("cursor" -> Json.fromString("bob-cursor")))
        _       <- store.clear(scope)
        cleared <- store.load(scope)
        _ = assertEquals(cleared, LocalCache.empty)
        bob <- store.load(others.head)
        _ = assertEquals(LocalCache.field(bob, "cursor"), "bob-cursor")
      } yield ()
    }
  }
  test("无效消息及 SQLite 写入失败均回滚消息/游标，失败后可继续提交") {
    database.use { path =>
      val store = new SqliteStore[IO](path)
      for {
        saved <- store.commit(scope, page)
        invalid <- store
          .commit(scope, Json.obj("cursor" -> Json.fromString("invalid"), "messages" -> Json.arr(Json.obj())))
          .attempt
        _ = assert(invalid.isLeft)
        _ <- execute(
          path,
          "CREATE TRIGGER reject_cache BEFORE UPDATE ON im_cache BEGIN SELECT RAISE(ABORT,'test write failure'); END"
        )
        failed <- store.commit(scope, Json.obj("cursor" -> Json.fromString("must-not-commit"))).attempt
        _ = assert(failed.isLeft)
        reopened <- new SqliteStore[IO](path).load(scope)
        _ = assertEquals(reopened, saved)
        _       <- execute(path, "DROP TRIGGER reject_cache")
        success <- store.commit(scope, Json.obj("cursor" -> Json.fromString("retry")))
        _ = assertEquals(LocalCache.field(success, "cursor"), "retry")
      } yield ()
    }
  }
  test("同一数据库多个实例并发写入不丢消息，未知 payload 原样保留") {
    database.use { path =>
      for {
        _ <- (1 to 16).toList.parTraverse_ { id =>
          new SqliteStore[IO](path)
            .commit(scope, Json.obj("messages" -> Json.arr(message(id)), "latest_seq" -> Json.fromString(id.toString)))
        }
        value <- new SqliteStore[IO](path).load(scope)
        _ = assertEquals(value.hcursor.downField("messages").focus.get.asObject.get.size, 16)
        _ = assertEquals(LocalCache.field(value, "latest_seq"), "16")
        _ = assertEquals(
          value.hcursor
            .downField("messages")
            .downField("message-1")
            .downField("payload")
            .get[String]("future")
            .toOption,
          Some("kept")
        )
      } yield ()
    }
  }
  test("单聊历史不回退 revision，快照 reset 原子替换当前账号") {
    database.use { path =>
      val store = new SqliteStore[IO](path)
      def conversation(revision: String, id: String) = Json.obj(
        "conversation_id" -> Json.fromString("direct_contract"),
        "revision"        -> Json.fromString(revision),
        "messages" -> Json.arr(fixture("direct-message.json").deepMerge(Json.obj("message_id" -> Json.fromString(id))))
      )
      for {
        _     <- store.commit(scope, Json.obj("conversations" -> Json.arr(conversation("2", "new"))))
        saved <- store.commit(scope, Json.obj("conversations" -> Json.arr(conversation("1", "old"))))
        chat = saved.hcursor.downField("conversations").downField("direct_contract")
        _    = assertEquals(chat.get[String]("revision").toOption, Some("2"))
        _    = assertEquals(chat.downField("messages").focus.get.asObject.get.size, 2)
        _     <- store.commit(scope.copy(userId = "bob"), page)
        reset <- store.commit(scope, Json.obj("messages" -> Json.arr(message(9))), reset = true)
        _ = assertEquals(reset.hcursor.downField("messages").focus.get.asObject.get.size, 1)
        _ = assertEquals(reset.hcursor.downField("conversations").focus.get.asObject.get.size, 0)
        bob <- store.load(scope.copy(userId = "bob"))
        _ = assertEquals(LocalCache.field(bob, "cursor"), "opaque-cursor")
      } yield ()
    }
  }
  test("WAL 读取不会等待其他连接的未提交写事务") {
    database.use { path =>
      val store = new SqliteStore[IO](path)
      for {
        saved <- store.commit(scope, page)
        _ <- Resource
          .fromAutoCloseable(IO.blocking(DriverManager.getConnection("jdbc:sqlite:" + path.toUri.toASCIIString)))
          .use { writer =>
            IO.blocking(Using.resource(writer.createStatement())(_.execute("BEGIN IMMEDIATE"))) *>
              store.load(scope).timeout(2.seconds).map(value => assertEquals(value, saved))
          }
      } yield ()
    }
  }
  test("损坏缓存读取失败后仍可清理，其他账号保留") {
    database.use { path =>
      val store = new SqliteStore[IO](path)
      for {
        _ <- store.commit(scope, page)
        _ <- store.commit(scope.copy(userId = "bob"), page)
        _ <- sql(path) { connection =>
          Using.resource(connection.prepareStatement("UPDATE im_cache SET data=? WHERE scope=?")) { statement =>
            statement.setString(1, "{broken"); statement.setString(2, scope.key); statement.executeUpdate()
          }
        }
        failed <- store.load(scope).attempt
        _ = assert(failed.isLeft)
        _       <- store.clear(scope)
        cleared <- store.load(scope)
        _ = assertEquals(cleared, LocalCache.empty)
        bob <- store.load(scope.copy(userId = "bob"))
        _ = assertEquals(LocalCache.field(bob, "cursor"), "opaque-cursor")
      } yield ()
    }
  }
  test("拒绝空路径和仅进程内内存数据库") {
    intercept[IllegalArgumentException](new SqliteStore[IO](Path.of("")))
    intercept[IllegalArgumentException](new SqliteStore[IO](Path.of(":memory:")))
    intercept[IllegalArgumentException](new SqliteStore[IO](Path.of("file::memory:")))
    intercept[IllegalArgumentException](new SqliteStore[IO](Path.of("file:cache?mode=memory&cache=shared")))
  }
}
