package polyglot.im.domain
import io.circe.Json
import io.circe.parser.decode

/** 合并同步页和单聊状态的纯函数，不让迟到响应回退缓存水位。 */
object LocalCache {
  val empty: Json = Json.obj(
    "messages"          -> Json.obj(),
    "cursor"            -> Json.Null,
    "event_position"    -> Json.fromString("0"),
    "latest_seq"        -> Json.fromString("0"),
    "read_seq"          -> Json.fromString("0"),
    "unread_count"      -> Json.fromString("0"),
    "chat_unread_count" -> Json.fromString("0")
  )

  /** 读取必填字符串字段；字段缺失或类型错误时拒绝继续解析。 */
  def field(value: Json, name: String): String = value.hcursor.get[String](name).toOption.get

  /** 按消息 ID 去重合并同步页，保留较新的会话版本和已读水位。 */
  def applyPage(current: Json, page: Json, reset: Boolean = false): Json = {
    val oldPosition = BigInt(field(current, "event_position"))
    if (
      page.hcursor
        .get[String]("history_position")
        .toOption
        .orElse(page.hcursor.get[String]("event_position").toOption)
        .exists(v => BigInt(v) < oldPosition)
    ) current
    else {
      var result   = if (reset) empty else current
      var messages = result.hcursor.downField("messages").focus.get.asObject.get
      val added = page.hcursor.get[List[Json]]("messages").getOrElse(Nil) ++ page.hcursor
        .get[List[Json]]("events")
        .getOrElse(Nil)
        .filter(e => e.hcursor.get[String]("type").toOption.exists(Set("message.created", "notification.updated")))
        .map(_.hcursor.downField("data").focus.get)
      added.foreach { value =>
        val m =
          decode[Message](value.noSpaces).toOption.getOrElse(throw new IllegalArgumentException("invalid message"));
        val previous = messages(m.messageId).flatMap(_.hcursor.get[String]("state_version").toOption).getOrElse("0")
        if (BigInt(previous) <= BigInt(m.stateVersion)) messages = messages.add(m.messageId, value)
      }
      result = result.deepMerge(Json.obj("messages" -> Json.fromJsonObject(messages)))
      var conversations =
        result.hcursor.downField("conversations").focus.flatMap(_.asObject).getOrElse(io.circe.JsonObject.empty)
      val patches = page.hcursor.get[List[Json]]("conversations").getOrElse(Nil) ++ page.hcursor
        .get[List[Json]]("events")
        .getOrElse(Nil)
        .filter(_.hcursor.get[String]("type").contains("conversation.updated"))
        .map(_.hcursor.downField("data").focus.get)
      patches.foreach { patch =>
        val id = field(patch, "conversation_id")
        val previous =
          conversations(id).getOrElse(Json.obj("revision" -> Json.fromString("0"), "messages" -> Json.obj()))
        var saved = previous.hcursor.downField("messages").focus.get.asObject.get
        patch.hcursor.get[List[Json]]("messages").getOrElse(Nil).foreach { value =>
          val m =
            decode[Message](value.noSpaces).toOption.getOrElse(throw new IllegalArgumentException("invalid message"))
          require(m.conversationId == id, "message conversation mismatch")
          saved = saved.add(m.messageId, value)
        }
        val metadata = if (BigInt(field(previous, "revision")) > BigInt(field(patch, "revision"))) previous else patch
        conversations = conversations.add(id, metadata.mapObject(_.add("messages", Json.fromJsonObject(saved))))
      }
      result = result.mapObject(_.add("conversations", Json.fromJsonObject(conversations)))
      List("cursor", "event_position", "latest_seq", "read_seq", "unread_count", "chat_unread_count", "conversation_id")
        .foreach(k => page.hcursor.downField(k).focus.foreach(v => result = result.deepMerge(Json.obj(k -> v))))
      List("latest_seq", "read_seq").foreach { name =>
        if (!reset && BigInt(field(current, name)) > BigInt(field(result, name)))
          result = result.deepMerge(Json.obj(name -> Json.fromString(field(current, name))))
      }
      // 通知已读同时受单条状态和水位控制，历史补页不能重新显示未读。
      val readThrough = BigInt(field(result, "read_seq"))
      val readMessages =
        result.hcursor.downField("messages").focus.get.asObject.get.toIterable.map { case (id, value) =>
          id -> (if (BigInt(field(value, "conversation_seq")) <= readThrough)
                   value.deepMerge(Json.obj("is_read" -> Json.True))
                 else value)
        }
      result = result.mapObject(_.add("messages", Json.obj(readMessages.toSeq*)))
      if (
        page.hcursor.downField("unread_count").succeeded || (!page.hcursor
          .downField("latest_seq")
          .succeeded && !page.hcursor.downField("read_seq").succeeded)
      ) result
      else
        result.deepMerge(
          Json.obj(
            "unread_count" -> Json.fromString(
              (BigInt(field(result, "latest_seq")) - BigInt(field(result, "read_seq"))).toString
            )
          )
        )
    }
  }
}
