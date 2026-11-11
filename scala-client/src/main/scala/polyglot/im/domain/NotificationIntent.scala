package polyglot.im.domain
import io.circe.Json

/** 宿主事务内记录的通知意图；事件 ID、接收者和载荷在重试时保持一致。 */
final case class NotificationIntent(eventId: String, recipient: String, kind: String, payload: Json)
