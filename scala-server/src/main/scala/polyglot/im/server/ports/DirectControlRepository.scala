package polyglot.im.server.ports

import io.circe.Json
import polyglot.im.server.domain.{Identity, PeerRelationship}

/** 单聊权限、个人可见性、设备租约和撤回的持久化事务端口，不访问外部业务服务。 */
trait DirectControlRepository[F[_]] {

  /** 按用户 ID 游标读取本人偏好或黑名单，不返回对方设置。 */
  def listPeerPreferences(who: Identity, after: String, limit: Int, blockedOnly: Boolean): F[Json]

  /** 读取当前成员会话中的对方身份，越权与不存在均返回同一错误。 */
  def directPeer(who: Identity, id: String): F[String]

  /** 读取／更新当前用户自己的私信与在线可见范围；未设置时默认仅互关。 */
  def directSettings(who: Identity): F[Json]

  /** 校验白名单范围并持久化；不写入设备推送全局偏好。 */
  def updateDirectSettings(who: Identity, data: Json): F[Json]

  /** 返回当前用户对某人的偏好及是否可以发送，不披露对方黑名单或隐私配置。 */
  def peerPreferences(who: Identity, peer: String, relationship: PeerRelationship): F[Json]

  /** 在同一用户对互斥锁中更新拉黑、免打扰或置顶，阻止并发发送绕过拉黑。 */
  def updatePeerPreferences(who: Identity, peer: String, data: Json): F[Json]

  /** 隐藏或清空截至所给序号的本人会话；较晚消息不受影响，不修改对方记录。 */
  def updateDirectVisibility(who: Identity, id: String, through: Long, hide: Boolean, clear: Boolean): F[Json]

  /** 本人删除或限时撤回，写入同步墓碑并重算未读，重放保持幂等。 */
  def updateDirectMessage(who: Identity, id: String, message: String, recall: Boolean): F[Json]

  /** 续约或关闭已激活设备在线租约，不以连接状态代替用户在线。 */
  def updatePresence(who: Identity, device: String, online: Boolean): F[Unit]

  /** 仅为双方开启、互关且未拉黑的指定用户返回租约状态，其余统一不可见。 */
  def presence(who: Identity, relationships: Map[String, PeerRelationship]): F[Json]
}
