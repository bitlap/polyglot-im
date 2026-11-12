package polyglot.im

import java.nio.file.{Files, Path}
import java.sql.{Connection, SQLException}
import javax.sql.DataSource
import scala.concurrent.duration.*
import scala.util.Using
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite

/** 真实临时 PostgreSQL 客户端缓存回归，不使用服务端消息表或生产数据库。 */
class PostgresStoreSuite extends CatsEffectSuite {
  private val scope = AccountScope("https://im.example", "app", "alice")
  private def fixture(name: String): Json =
    parse(Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol/fixtures", name))).toOption.get
  private def database =
    Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(pg => IO.blocking(pg.close())).evalMap {
      pg =>
        val source = pg.getPostgresDatabase
        val store  = new PostgresStore[IO](source, "device")
        store.initialize.as((source, store))
    }
  private def sql[A](source: DataSource)(run: Connection => A): IO[A] = IO.blocking {
    Using.resource(source.getConnection)(run)
  }
  private def execute(source: DataSource, command: String): IO[Unit] = sql(source) { c =>
    Using.resource(c.createStatement()) { statement =>
      statement.execute(command); ()
    }
  }
  private def message(id: Int): Json = fixture("message.json").deepMerge(
    Json.obj(
      "message_id"       -> Json.fromString(s"pg-message-$id"),
      "conversation_seq" -> Json.fromString(id.toString),
      "type"             -> Json.fromString("custom.future"),
      "payload"          -> Json.obj("text" -> Json.fromString("你好 👨‍👩‍👧‍👦"), "future" -> Json.fromString("kept"))
    )
  )

  test("共享契约与大序号持久化，重建实例恢复，namespace/账号/环境/应用隔离") {
    database.use { case (source, store) =>
      val others =
        List(scope.copy(userId = "bob"), scope.copy(appId = "other"), scope.copy(environment = "https://other"))
      val other = new PostgresStore[IO](source, "other")
      for {
        saved <- store.commit(scope, fixture("sync.json"))
        _ = assertEquals(LocalCache.field(saved, "latest_seq"), "9007199254740993")
        reopened <- new PostgresStore[IO](source, "device").load(scope)
        _ = assertEquals(reopened, saved)
        isolated <- other.load(scope)
        _ = assertEquals(isolated, LocalCache.empty)
        _ <- other.commit(scope, Json.obj("cursor" -> Json.fromString("other")))
        _ <- others.traverse_ { s =>
          store.load(s).map(value => assertEquals(value, LocalCache.empty)) *>
            store.commit(s, Json.obj("cursor" -> Json.fromString(s.key))).void
        }
        _          <- store.clear(scope)
        savedOther <- other.load(scope)
        _ = assertEquals(LocalCache.field(savedOther, "cursor"), "other")
        _ <- others.traverse_(s => store.load(s).map(value => assertEquals(LocalCache.field(value, "cursor"), s.key)))
      } yield ()
    }
  }
  test("首次初始化及 16 个实例并发写入不丢消息，未知 payload 保留") {
    database.use { case (source, store) =>
      for {
        _ <- (1 to 16).toList.parTraverse_ { id =>
          val current = new PostgresStore[IO](source, "device")
          current.initialize *> current
            .commit(scope, Json.obj("messages" -> Json.arr(message(id)), "latest_seq" -> Json.fromString(id.toString)))
            .void
        }
        saved <- store.load(scope)
        _ = assertEquals(saved.hcursor.downField("messages").focus.get.asObject.get.size, 16)
        _ = assertEquals(LocalCache.field(saved, "latest_seq"), "16")
        _ = assertEquals(
          saved.hcursor
            .downField("messages")
            .downField("pg-message-1")
            .downField("payload")
            .get[String]("future")
            .toOption,
          Some("kept")
        )
      } yield ()
    }
  }
  test("校验和 SQL 失败均回滚游标，快照和损坏缓存清理可恢复") {
    database.use { case (source, store) =>
      for {
        saved <- store.commit(scope, fixture("sync.json"))
        invalid <- store
          .commit(scope, Json.obj("cursor" -> Json.fromString("invalid"), "messages" -> Json.arr(Json.obj())))
          .attempt
        _ = assert(invalid.isLeft)
        _ <- execute(
          source,
          "CREATE FUNCTION snap_flow.reject_cache() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test write failure'; END $$"
        )
        _ <- execute(
          source,
          "CREATE TRIGGER reject_cache BEFORE UPDATE ON snap_flow.im_client_cache FOR EACH ROW EXECUTE FUNCTION snap_flow.reject_cache()"
        )
        failed <- store.commit(scope, Json.obj("cursor" -> Json.fromString("failed"))).attempt
        _ = assert(failed.isLeft)
        restored <- store.load(scope)
        _ = assertEquals(restored, saved)
        _     <- execute(source, "DROP TRIGGER reject_cache ON snap_flow.im_client_cache")
        reset <- store.commit(scope, Json.obj("messages" -> Json.arr(message(2))), reset = true)
        _ = assertEquals(reset.hcursor.downField("messages").focus.get.asObject.get.keys.toList, List("pg-message-2"))
        _      <- execute(source, "UPDATE snap_flow.im_client_cache SET data='{broken'")
        broken <- store.load(scope).attempt
        _ = assert(broken.isLeft)
        _       <- store.clear(scope)
        cleared <- store.load(scope)
        _ = assertEquals(cleared, LocalCache.empty)
        retried <- store.commit(scope, Json.obj("cursor" -> Json.fromString("retry")))
        _ = assertEquals(LocalCache.field(retried, "cursor"), "retry")
      } yield ()
    }
  }
  test("作用域锁有界等待，普通读取不占写锁，超时不破坏缓存或连接") {
    database.use { case (source, store) =>
      val held = Resource.make(IO.blocking {
        val c = source.getConnection
        try {
          c.setAutoCommit(false)
          Using.resource(c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,674239012))")) { s =>
            s.setString(1, "device\n" + scope.key); s.execute()
          }
          c
        } catch { case error: Throwable => c.close(); throw error }
      })(c =>
        IO.blocking {
          try c.rollback()
          finally c.close()
        }
      )
      for {
        saved <- store.commit(scope, Json.obj("cursor" -> Json.fromString("saved")))
        _ <- held.use { _ =>
          for {
            read <- store.load(scope).timeout(2.seconds)
            _ = assertEquals(read, saved)
            failed <- new PostgresStore[IO](source, "device", lockTimeoutMillis = 100)
              .commit(scope, Json.obj("cursor" -> Json.fromString("failed")))
              .attempt
            _ = assert(failed.left.toOption.exists {
              case e: SQLException => e.getSQLState == "55P03"; case _ => false
            })
          } yield ()
        }
        read <- store.load(scope)
        _ = assertEquals(read, saved)
        retry <- store.commit(scope, Json.obj("cursor" -> Json.fromString("retry")))
        _ = assertEquals(LocalCache.field(retry, "cursor"), "retry")
      } yield ()
    }
  }
  test("拒绝空 namespace、无效 schema 和无限锁等待") {
    IO {
      intercept[IllegalArgumentException](new PostgresStore[IO](null, ""))
      intercept[IllegalArgumentException](new PostgresStore[IO](null, "x", schema = "public; DROP SCHEMA public"))
      intercept[IllegalArgumentException](new PostgresStore[IO](null, "x", lockTimeoutMillis = 0))
    }
  }
}
