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
    createdAt: String,
    isRead: Boolean = false,
    isDeleted: Boolean = false,
    stateVersion: String = "0"
) {
  require(stateVersion.matches("[0-9]+"), "invalid state_version")
  require(schemaVersion == 1, "unsupported schema_version")
  require(messageId.nonEmpty && conversationId.nonEmpty && messageType.nonEmpty, "empty identity or type")
  require(conversationSeq.matches("[1-9][0-9]*"), "invalid conversation_seq")
  require(payload.isObject, "payload must be an object")
  require(createdAt.endsWith("Z"), "created_at must be UTC")
  Instant.parse(createdAt)
}

/** 消息 v1 的 Circe 编解码器，校验协议字段并保留未知类型载荷。 */
object Message {
  private val fullEncoder: Encoder[Message] = Encoder.forProduct10(
    "schema_version",
    "message_id",
    "conversation_id",
    "conversation_seq",
    "type",
    "payload",
    "created_at",
    "is_read",
    "is_deleted",
    "state_version"
  )(m =>
    (
      m.schemaVersion,
      m.messageId,
      m.conversationId,
      m.conversationSeq,
      m.messageType,
      m.payload,
      m.createdAt,
      m.isRead,
      m.isDeleted,
      m.stateVersion
    )
  )

  given Encoder[Message] = Encoder.instance(m =>
    fullEncoder(m).mapObject(o =>
      o.filter { case (k, v) =>
        !(Set("is_read", "is_deleted")
          .contains(k) && v == Json.False) && !(k == "state_version" && v == Json.fromString("0"))
      }
    )
  )

  given Decoder[Message] = Decoder.instance { cursor =>
    for {
      schemaVersion   <- cursor.get[Int]("schema_version")
      messageId       <- cursor.get[String]("message_id")
      conversationId  <- cursor.get[String]("conversation_id")
      conversationSeq <- cursor.get[String]("conversation_seq")
      messageType     <- cursor.get[String]("type")
      payload         <- cursor.get[Json]("payload")
      createdAt       <- cursor.get[String]("created_at")
      isRead          <- cursor.get[Option[Boolean]]("is_read").map(_.getOrElse(false))
      isDeleted       <- cursor.get[Option[Boolean]]("is_deleted").map(_.getOrElse(false))
      version         <- cursor.get[Option[String]]("state_version").map(_.getOrElse("0"))
      message <- scala.util
        .Try(
          Message(
            schemaVersion,
            messageId,
            conversationId,
            conversationSeq,
            messageType,
            payload,
            createdAt,
            isRead,
            isDeleted,
            version
          )
        )
        .toEither
        .left
        .map(e => io.circe.DecodingFailure(e.getMessage, cursor.history))
    } yield message
  }
}
