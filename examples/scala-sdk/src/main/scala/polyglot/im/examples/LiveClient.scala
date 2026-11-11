package polyglot.im.examples

import java.net.URI
import java.nio.file.{Files, Path}
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import polyglot.im.*
import polyglot.im.transport.*

object LiveClient extends IOApp {
  def run(args: List[String]): IO[ExitCode] = for {
    config <- IO.blocking {
      val path = args.headOption
        .map(Path.of(_))
        .getOrElse(
          Path.of(sys.props("polyglot.im.root"), ".local-demo", "config.json")
        )
      parse(Files.readString(path)).toOption.get
    }
    _ <- JdkTransport.resource[IO](URI.create(config.hcursor.get[String]("base_url").toOption.get)).use {
      case (transport, _) =>
        def api(field: String) = new ApiClient[IO](
          new AuthenticatedTransport[IO](
            transport,
            new TokenProvider[IO] {
              def token: IO[String] = IO.pure(config.hcursor.get[String](field).toOption.get)
            }
          )
        )
        val sender = new NotificationSender[IO](api("service_token"))
        val payload =
          Json.obj("task_id" -> Json.fromString("scala-demo-task"), "text" -> Json.fromString("来自 Scala 的通知"))
        for {
          first     <- sender.send("alice", "scala-demo-task", "task.completed", payload)
          duplicate <- sender.send("alice", "scala-demo-task", "task.completed", payload)
          _ <- IO.raiseWhen(first.messageId != duplicate.messageId)(new IllegalStateException("idempotency failed"))
          history <- api("user_token").request("GET", "/messages")
          _ <- IO.raiseUnless(
            history.hcursor
              .get[List[Json]]("items")
              .toOption
              .get
              .exists(_.hcursor.get[String]("message_id").contains(first.messageId))
          )(new IllegalStateException("history failed"))
          _ <- IO.println("Scala SDK HTTP + idempotency + history: passed")
        } yield ()
    }
  } yield ExitCode.Success
}
