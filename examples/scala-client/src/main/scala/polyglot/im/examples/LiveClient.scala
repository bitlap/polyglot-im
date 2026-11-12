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
      case (transport, socket) =>
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
          directory <- IO.blocking(Files.createTempDirectory("im-direct-scala"))
          _ <- cats.effect.Resource
            .make(IO.pure(directory))(path =>
              IO.blocking {
                val files = Files.walk(path)
                try files.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
                finally files.close()
              }
            )
            .use { path =>
              val base  = config.hcursor.get[String]("base_url").toOption.get
              val app   = config.hcursor.get[String]("app_id").toOption.get
              val scope = AccountScope(base, app, "alice")
              val store = new SqliteStore[IO](path.resolve("im.db"))
              for {
                client <- ImClient.create(
                  api("user_token"),
                  scope,
                  "scala-chat",
                  store,
                  socket
                )
                _            <- client.initialize
                conversation <- client.createDirect("bob")
                id = conversation.hcursor.get[String]("conversation_id").toOption.get
                sent      <- client.sendText(id, "scala-chat", "你好 😊👍🏽")
                duplicate <- client.sendText(id, "scala-chat", "你好 😊👍🏽")
                _ <- IO.raiseWhen(sent.messageId != duplicate.messageId)(
                  new IllegalStateException("chat idempotency failed")
                )
                page <- client.conversationHistory(id)
                _ <- IO.raiseUnless(
                  page.hcursor
                    .get[List[Json]]("items")
                    .toOption
                    .get
                    .exists(_.hcursor.get[String]("message_id").contains(sent.messageId))
                )(new IllegalStateException("chat history failed"))
                _        <- client.markConversationRead(id, sent.conversationSeq)
                _        <- client.close()
                reopened <- new SqliteStore[IO](path.resolve("im.db")).load(scope)
                _ <- IO.raiseUnless(
                  reopened.hcursor
                    .downField("conversations")
                    .downField(id)
                    .downField("messages")
                    .downField(sent.messageId)
                    .succeeded
                )(new IllegalStateException("SQLite restart cache failed"))
              } yield ()
            }
          _ <- IO.println("Scala Client SQLite restart + direct chat/emoji + HTTP + idempotency + history: passed")
        } yield ()
    }
  } yield ExitCode.Success
}
