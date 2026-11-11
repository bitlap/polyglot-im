package polyglot.im.server

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.circe.{Json, Printer}
import io.circe.parser.parse

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

object Protocol {
  val knownTypes = Set(
    "task.completed",
    "task.failed",
    "comment.created",
    "comment.replied",
    "like.created",
    "favorite.created",
    "system.notice"
  )
  val actions                          = Set("open_photo", "open_item", "open_task", "none")
  def id: String                       = UUID.randomUUID().toString
  def now: Long                        = Instant.now().getEpochSecond
  def time: String                     = Instant.now().toString
  def b64(value: Array[Byte]): String  = Base64.getUrlEncoder.withoutPadding().encodeToString(value)
  def un64(value: String): Array[Byte] = Base64.getUrlDecoder.decode(value)
  def digest(value: String): String    = b64(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
  def hmac(secret: String, value: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    b64(mac.doFinal(value.getBytes(UTF_8)))
  }
  def equal(a: String, b: String): Boolean            = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))
  def conversation(app: String, user: String): String = "notice_" + digest(app + "\u0000" + user)
  def canonical(json: Json): String                   = Printer.noSpaces.copy(sortKeys = true).print(json)
  def field(json: Json, name: String): String = json.hcursor
    .get[String](name)
    .toOption
    .filter(_.nonEmpty)
    .getOrElse(throw ApiError(422, "INVALID_ARGUMENT", s"missing $name"))
  def bounded(value: String, name: String, max: Int = 200): String = {
    if (value.isEmpty || value.codePointCount(0, value.length) > max || value.exists(c => c < ' '))
      throw ApiError(422, "INVALID_ARGUMENT", s"invalid $name")
    value
  }
  def number(value: String): Long =
    scala.util
      .Try(if (value.matches("0|[1-9][0-9]*")) value.toLong else throw new IllegalArgumentException())
      .filter(_ >= 0)
      .getOrElse(throw ApiError(422, "INVALID_ARGUMENT", "invalid sequence"))
  def notification(json: Json): Notification = {
    val recipient = bounded(field(json, "recipient_id"), "recipient_id")
    val key       = bounded(field(json, "client_message_id"), "client_message_id")
    val kind      = bounded(field(json, "type"), "type")
    if (!knownTypes(kind) && !kind.matches("custom\\.[a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+"))
      throw ApiError(422, "UNSUPPORTED_MESSAGE_TYPE", "unsupported type")
    val payload = json.hcursor
      .downField("payload")
      .focus
      .filter(_.isObject)
      .getOrElse(throw ApiError(422, "INVALID_ARGUMENT", "payload must be an object"))
    if (payload.noSpaces.getBytes(UTF_8).length > 16384)
      throw ApiError(413, "PAYLOAD_TOO_LARGE", "payload exceeds 16 KiB")
    List("title", "text").foreach { name =>
      payload.hcursor.downField(name).focus.foreach { value =>
        val text = value.asString.getOrElse(throw ApiError(422, "INVALID_ARGUMENT", s"$name must be a string"))
        if (text.codePointCount(0, text.length) > 2048) throw ApiError(422, "INVALID_ARGUMENT", s"$name too long")
      }
    }
    val required =
      if (kind.startsWith("task.")) List("task_id")
      else if (Set("comment.created", "comment.replied")(kind)) List("item_id", "comment_id")
      else if (Set("like.created", "favorite.created")(kind)) List("item_id")
      else Nil
    required.foreach(k => bounded(field(payload, k), k))
    payload.hcursor.downField("action").focus.foreach { action =>
      val actionType = field(action, "type")
      if (!actions(actionType)) throw ApiError(422, "INVALID_ARGUMENT", "unsupported action")
      if (actionType != "none") bounded(field(action, "id"), "action.id")
    }
    Notification(recipient, key, kind, payload)
  }
}

final class CursorCodec(secret: String) {
  require(secret.getBytes(UTF_8).length >= 32, "cursor signing secret must be at least 32 bytes")
  def encode(who: Identity, value: Long): String = {
    val body = Protocol.b64(
      Json
        .obj("a" -> Json.fromString(who.app), "u" -> Json.fromString(who.user), "n" -> Json.fromString(value.toString))
        .noSpaces
        .getBytes(UTF_8)
    )
    body + "." + Protocol.hmac(secret, body)
  }
  def decode(who: Identity, value: Option[String]): Long = value
    .filter(_.nonEmpty)
    .map { token =>
      try {
        val parts = token.split("\\.", -1)
        if (parts.length != 2 || !Protocol.equal(Protocol.hmac(secret, parts(0)), parts(1)))
          throw new IllegalArgumentException()
        val json = parse(new String(Protocol.un64(parts(0)), UTF_8)).toOption.get
        if (Protocol.field(json, "a") != who.app || Protocol.field(json, "u") != who.user)
          throw new IllegalArgumentException()
        Protocol.number(Protocol.field(json, "n"))
      } catch { case _: Exception => throw ApiError(400, "INVALID_CURSOR", "cursor does not belong to this account") }
    }
    .getOrElse(0L)
}
