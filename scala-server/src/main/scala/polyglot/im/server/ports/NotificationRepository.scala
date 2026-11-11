package polyglot.im.server.ports
import polyglot.im.server.domain.{Identity, Notification, StoredMessage}

/** 原子保存通知及其事件、水位和推送意图的仓储端口。 */
trait NotificationRepository[F[_]] {

  /** 以调用方已校验的服务身份幂等提交通知；返回消息及是否首次创建，同键不同内容拒绝写入。 */
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)]
}
