package polyglot.im.server.domain

import io.circe.Json

/** 服务端可映射为 HTTP 错误信封的业务异常，不包含敏感配置。 */
final case class ApiError(status: Int, code: String, message: String) extends RuntimeException(message)

/** 认证后的应用、主体、角色与授权范围，用于每次资源访问检查。 */
final case class Identity(
    app: String,
    user: String,
    role: String,
    expires: Long,
    types: Set[String] = Set.empty,
    recipients: Set[String] = Set.empty
)

/** 已解析的通知命令，包含接收用户、稳定幂等键、类型及载荷。 */
final case class Notification(recipient: String, key: String, kind: String, payload: Json)

/** 一个用户的事件、消息、已读、留存水位及单聊未读统计。 */
final case class Position(
    cursor: Long,
    seq: Long,
    read: Long,
    retained: Long,
    directUnread: Long = 0,
    notificationUnread: Long = -1
) {

  /** 返回事务维护的通知未读数；未提供时兼容原有水位模型。 */
  def unread: Long = if (notificationUnread >= 0) notificationUnread else (seq - read).max(0)
}

/** 可靠存储中的消息记录，可转换为对外协议消息。 */
final case class StoredMessage(
    id: String,
    app: String,
    user: String,
    seq: Long,
    kind: String,
    payload: Json,
    created: String,
    directConversation: Option[String] = None,
    isRead: Boolean = false,
    isDeleted: Boolean = false,
    stateVersion: Long = 0
) {

  /** 转换为对外消息对象，序号采用字符串避免跨语言精度丢失。 */
  def json: Json = Json.obj(
    "schema_version"   -> Json.fromInt(1),
    "message_id"       -> Json.fromString(id),
    "conversation_id"  -> Json.fromString(directConversation.getOrElse(Protocol.conversation(app, user))),
    "conversation_seq" -> Json.fromString(seq.toString),
    "type"             -> Json.fromString(kind),
    "payload"          -> payload,
    "created_at"       -> Json.fromString(created),
    "is_read"          -> Json.fromBoolean(isRead),
    "is_deleted"       -> Json.fromBoolean(isDeleted),
    "state_version"    -> Json.fromString(stateVersion.toString)
  )
}

/** 已领取的推送任务，携带租约标识以拒绝过期 Worker 回写。 */
final case class PushJob(
    id: String,
    app: String,
    user: String,
    message: String,
    device: String,
    attempts: Int,
    claimToken: String
)

/** 本次投递使用的设备 token、隐私偏好、角标和消息。 */
final case class PushTarget(token: String, preview: Boolean, unread: Long, message: StoredMessage)

/** 推送结果分类；rejected 表示提供商明确拒绝，invalidToken 仅用于明确失效的设备令牌。 */
final case class PushResult(
    retry: Boolean,
    invalidToken: Boolean = false,
    reason: String = "",
    rejected: Boolean = false
)
