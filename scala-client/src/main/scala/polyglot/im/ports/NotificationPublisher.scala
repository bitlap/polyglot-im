package polyglot.im.ports
import io.circe.Json
import polyglot.im.domain.Message

/** 业务通知发布端口；接受成功表示 IM 已提交，不表示用户已读。 */
trait NotificationPublisher[F[_]] {

  /** 使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。 */
  def send(recipientId: String, clientMessageId: String, kind: String, payload: Json): F[Message]
}
