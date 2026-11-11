package polyglot.im.server.domain

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import io.circe.{Json, Printer}

/** 校验通知输入并生成消息身份、UTC 时间及规范化载荷。 */
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
  val actions = Set("open_photo", "open_item", "open_task", "none")

  /** 生成新的随机消息或事件身份，不用数据库序号代替幂等键。 */
  def id: String = UUID.randomUUID().toString

  /** 返回当前 UTC Unix 秒数，供凭证有效期和租约计算使用。 */
  def now: Long = Instant.now().getEpochSecond

  /** 返回当前 UTC RFC 3339 时间文本。 */
  def time: String = Instant.now().toString
  export polyglot.im.server.common.Crypto.{b64, un64, digest, hmac, equal}

  /** 由应用和用户生成稳定的通知会话身份。 */
  def conversation(app: String, user: String): String = "notice_" + digest(app + "\u0000" + user)

  /** 按字段名排序生成紧凑 JSON，用于幂等内容比较。 */
  def canonical(json: Json): String = Printer.noSpaces.copy(sortKeys = true).print(json)

  /** 读取必填字符串字段；字段缺失或类型错误时拒绝继续解析。 */
  def field(json: Json, name: String): String = json.hcursor
    .get[String](name)
    .toOption
    .filter(_.nonEmpty)
    .getOrElse(throw ApiError(422, "INVALID_ARGUMENT", s"missing $name"))

  /** 校验非空文本及长度上限，不将非法值回显到错误中。 */
  def bounded(value: String, name: String, max: Int = 200): String = {
    if (value.isEmpty || value.codePointCount(0, value.length) > max || value.exists(c => c < ' '))
      throw ApiError(422, "INVALID_ARGUMENT", s"invalid $name")
    value
  }

  /** 解析规范的非负 64 位序号文本，拒绝前导零、符号和溢出。 */
  def number(value: String): Long =
    scala.util
      .Try(if (value.matches("0|[1-9][0-9]*")) value.toLong else throw new IllegalArgumentException())
      .filter(_ >= 0)
      .getOrElse(throw ApiError(422, "INVALID_ARGUMENT", "invalid sequence"))

  /** 解析通知命令并校验类型、资源身份、长度和受控跳转。 */
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
