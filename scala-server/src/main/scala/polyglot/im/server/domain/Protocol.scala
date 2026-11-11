package polyglot.im.server.domain

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import io.circe.{Json, Printer}

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
  val actions      = Set("open_photo", "open_item", "open_task", "none")
  def id: String   = UUID.randomUUID().toString
  def now: Long    = Instant.now().getEpochSecond
  def time: String = Instant.now().toString
  export polyglot.im.server.common.Crypto.{b64, un64, digest, hmac, equal}
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
