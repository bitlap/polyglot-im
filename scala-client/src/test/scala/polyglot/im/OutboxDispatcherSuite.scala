package polyglot.im

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

/** 替代持久化端口验证派发策略，核心无需 DataSource、JDBC 或网络客户端。 */
class OutboxDispatcherSuite extends CatsEffectSuite {
  private val event   = NotificationIntent("stable-id", "alice", "task.completed", Json.obj())
  private val message = Message(1, "message", "system", "1", "task.completed", Json.obj(), "2026-11-15T00:00:00Z")
  private def publisher(result: IO[Message]) = new NotificationPublisher[IO] {
    def send(recipientId: String, clientMessageId: String, kind: String, payload: Json): IO[Message] =
      IO {
        assertEquals(
          (recipientId, clientMessageId, kind, payload),
          (event.recipient, event.eventId, event.kind, event.payload)
        )
      } *> result
  }
  test("无数据库派发：成功、永久失败、认证、服务异常及网络失败保持原分类与退避上限") {
    val cases = List(
      (IO.pure(message), OutboxState.Sent, None),
      (IO.raiseError[Message](ImError(403, "DENIED", "private")), OutboxState.Dead, Some("DENIED")),
      (IO.raiseError[Message](ImError(401, "AUTH", "private")), OutboxState.Pending, Some("AUTH")),
      (IO.raiseError[Message](ImError(503, "UNAVAILABLE", "private")), OutboxState.Pending, Some("UNAVAILABLE")),
      (IO.raiseError[Message](new java.io.IOException("private")), OutboxState.Pending, Some("NETWORK_ERROR"))
    )
    cases.traverse_ { (effect, state, code) =>
      for {
        saved <- Ref.of[IO, List[OutboxCompletion]](Nil)
        row = OutboxClaim(event, 9, "lease")
        outbox = new Outbox[IO] {
          def claim(seconds: Long): IO[Option[OutboxClaim]] = IO { assertEquals(seconds, 23L); Some(row) }
          def complete(claim: OutboxClaim, result: OutboxCompletion): IO[Boolean] =
            IO(assertEquals(claim, row)) *> saved.update(_ :+ result).as(false)
        }
        found   <- new OutboxDispatcher[IO](outbox, publisher(effect), 23).drainOnce
        results <- saved.get
        _ = assert(found)
        _ = assertEquals(results, List(OutboxCompletion(state, 300, code)))
      } yield ()
    }
  }
  test("无可领取记录不发送，未知异常与取消不确认") {
    for {
      completed <- Ref.of[IO, Int](0)
      started   <- Deferred[IO, Unit]
      outbox = new Outbox[IO] {
        def claim(seconds: Long): IO[Option[OutboxClaim]] = IO.pure(Some(OutboxClaim(event, 0, "lease")))
        def complete(row: OutboxClaim, result: OutboxCompletion): IO[Boolean] = completed.update(_ + 1).as(true)
      }
      empty = new Outbox[IO] {
        def claim(seconds: Long): IO[Option[OutboxClaim]] = IO.pure(None)
        def complete(row: OutboxClaim, result: OutboxCompletion): IO[Boolean] =
          IO.raiseError(new Exception("unexpected"))
      }
      idle <- new OutboxDispatcher[IO](empty, publisher(IO.raiseError(new Exception("unexpected")))).drainOnce
      unknown <- new OutboxDispatcher[IO](
        outbox,
        publisher(IO.raiseError(new IllegalStateException("unknown")))
      ).drainOnce.attempt
      fiber <- new OutboxDispatcher[IO](outbox, publisher(started.complete(()).void *> IO.never)).drainOnce.start
      _     <- started.get *> fiber.cancel
      count <- completed.get
      _ = assert(!idle && unknown.isLeft)
      _ = assertEquals(count, 0)
      _ = intercept[IllegalArgumentException](new OutboxDispatcher[IO](outbox, publisher(IO.pure(message)), 0))
    } yield ()
  }
}
