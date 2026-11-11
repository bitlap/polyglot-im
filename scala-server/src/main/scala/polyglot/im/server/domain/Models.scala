package polyglot.im.server.domain

import io.circe.Json

final case class ApiError(status: Int, code: String, message: String) extends RuntimeException(message)
final case class Identity(
    app: String,
    user: String,
    role: String,
    expires: Long,
    types: Set[String] = Set.empty,
    recipients: Set[String] = Set.empty
)
final case class Notification(recipient: String, key: String, kind: String, payload: Json)
final case class Position(cursor: Long, seq: Long, read: Long, retained: Long, directUnread: Long = 0) {
  def unread: Long = (seq - read).max(0)
}
final case class StoredMessage(
    id: String,
    app: String,
    user: String,
    seq: Long,
    kind: String,
    payload: Json,
    created: String,
    directConversation: Option[String] = None
) {
  def json: Json = Json.obj(
    "schema_version"   -> Json.fromInt(1),
    "message_id"       -> Json.fromString(id),
    "conversation_id"  -> Json.fromString(directConversation.getOrElse(Protocol.conversation(app, user))),
    "conversation_seq" -> Json.fromString(seq.toString),
    "type"             -> Json.fromString(kind),
    "payload"          -> payload,
    "created_at"       -> Json.fromString(created)
  )
}
final case class PushJob(
    id: String,
    app: String,
    user: String,
    message: String,
    device: String,
    attempts: Int,
    claimToken: String
)
final case class PushTarget(token: String, preview: Boolean, unread: Long, message: StoredMessage)
final case class PushResult(retry: Boolean, invalidToken: Boolean = false, reason: String = "")
