package polyglot.im.server

import polyglot.im.server.config.RateLimits
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Notification
import polyglot.im.server.domain.StoredMessage
import polyglot.im.server.ports.Authenticator
import polyglot.im.server.ports.RateLimiter

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import polyglot.im.server.ports.NotificationRepository
import polyglot.im.server.service.{NotificationService, AccessControl}

class ServicePortsSuite extends CatsEffectSuite {
  test("通知应用服务只通过仓储端口访问持久化，越权不触发写入") {
    val sender  = Identity("app", "producer", "service", Long.MaxValue, Set("system.notice"), Set("alice"))
    val command = Notification("alice", "stable-key", "system.notice", Json.obj())
    val message = StoredMessage("id", "app", "alice", 1L, "system.notice", Json.obj(), "2026-11-11T00:00:00Z")
    for {
      calls <- Ref.of[IO, Int](0)
      repository = new NotificationRepository[IO] {
        def send(who: Identity, value: Notification): IO[(StoredMessage, Boolean)] =
          calls.update(_ + 1).as((message, true))
      }
      service = new NotificationService[IO](repository)
      wrongUser   <- service.send(sender.copy(role = "user"), command).attempt
      wrongTarget <- service.send(sender, command.copy(recipient = "bob")).attempt
      before      <- calls.get
      _ = assert(wrongUser.isLeft && wrongTarget.isLeft)
      _ = assertEquals(before, 0)
      result <- service.send(sender, command)
      after  <- calls.get
      _ = assertEquals(result, (message, true))
      _ = assertEquals(after, 1)
    } yield ()
  }
  test("鉴权与集群配额策略不依赖 HTTP；身份不匹配不消耗限流配额") {
    val identity = Identity("app", "alice", "user", Long.MaxValue)
    for {
      quotas <- Ref.of[IO, List[Int]](Nil)
      auth = new Authenticator[IO] { def authenticate(token: String): IO[Identity] = IO.pure(identity) }
      limiter = new RateLimiter[IO] {
        def allow(who: Identity, maximum: Int): IO[Boolean] = quotas.update(maximum :: _).as(true)
      }
      access = new AccessControl[IO](auth, limiter, RateLimits(17, 3))
      wrong        <- access.authorize("token", "user", Some("app"), Some("bob")).attempt
      rejectedRole <- access.authorize("token", "service", None, None).attempt
      before       <- quotas.get
      _ = assert(wrong.isLeft && rejectedRole.isLeft)
      _ = assertEquals(before, Nil)
      accepted <- access.authorize("token", "user", Some("app"), Some("alice"))
      after    <- quotas.get
      _ = assertEquals(accepted, identity)
      _ = assertEquals(after, List(17))
    } yield ()
  }
}
