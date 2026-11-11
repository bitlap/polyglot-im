package polyglot.im.service

import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.decode
import polyglot.im.domain.Message
import polyglot.im.ports.{RemoteApi, NotificationPublisher}

/** 以服务身份发布业务通知；同一业务事件的重试必须复用幂等键。 */
final class NotificationSender[F[_]: Async](api: RemoteApi[F]) extends NotificationPublisher[F] {

  /** 使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。 */
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
