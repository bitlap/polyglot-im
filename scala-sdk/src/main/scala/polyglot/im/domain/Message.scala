package polyglot.im.domain

import java.time.Instant

import io.circe.{Decoder, Encoder, Json}

/** 协议 v1 消息。未知消息类型和 payload 保留；序号使用十进制字符串。 */
final case class Message(
    schemaVersion: Int,
    messageId: String,
    conversationId: String,
    conversationSeq: String,
    messageType: String,
    payload: Json,
    createdAt: String
) {
  require(schemaVersion == 1, "unsupported schema_version")
  require(messageId.nonEmpty && conversationId.nonEmpty && messageType.nonEmpty, "empty identity or type")
  require(conversationSeq.matches("[1-9][0-9]*"), "invalid conversation_seq")
  require(payload.isObject, "payload must be an object")
  require(createdAt.endsWith("Z"), "created_at must be UTC")
  Instant.parse(createdAt)
}

object Message {
  given Encoder[Message] = Encoder.forProduct7(
    "schema_version",
    "message_id",
    "conversation_id",
    "conversation_seq",
    "type",
    "payload",
    "created_at"
  )(m => (m.schemaVersion, m.messageId, m.conversationId, m.conversationSeq, m.messageType, m.payload, m.createdAt))

  given Decoder[Message] = Decoder.instance { cursor =>
    for {
      schemaVersion   <- cursor.get[Int]("schema_version")
      messageId       <- cursor.get[String]("message_id")
      conversationId  <- cursor.get[String]("conversation_id")
      conversationSeq <- cursor.get[String]("conversation_seq")
      messageType     <- cursor.get[String]("type")
      payload         <- cursor.get[Json]("payload")
      createdAt       <- cursor.get[String]("created_at")
      message <- scala.util
        .Try(Message(schemaVersion, messageId, conversationId, conversationSeq, messageType, payload, createdAt))
        .toEither
        .left
        .map(e => io.circe.DecodingFailure(e.getMessage, cursor.history))
    } yield message
  }
}
