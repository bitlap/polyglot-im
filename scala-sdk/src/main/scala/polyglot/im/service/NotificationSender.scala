package polyglot.im.service

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import polyglot.im.domain.Message
import polyglot.im.ports.{RemoteApi, NotificationPublisher}

final class NotificationSender[F[_]: Async](api: RemoteApi[F]) extends NotificationPublisher[F] {
  def send(recipientId: String, clientMessageId: String, kind: String, payload: Json): F[Message] =
    if (clientMessageId.isEmpty) Async[F].raiseError(new IllegalArgumentException("stable idempotency key required"))
    else
      api
        .request(
          "POST",
          "/notifications",
          Some(
            Json.obj(
              "recipient_id"      -> Json.fromString(recipientId),
              "client_message_id" -> Json.fromString(clientMessageId),
              "type"              -> Json.fromString(kind),
              "payload"           -> payload
            )
          )
        )
        .flatMap(j => Async[F].fromEither(decode[Message](j.noSpaces)))
}
