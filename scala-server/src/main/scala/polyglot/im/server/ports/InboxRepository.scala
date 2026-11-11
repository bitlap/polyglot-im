package polyglot.im.server.ports

import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Notification
import polyglot.im.server.domain.Position
import polyglot.im.server.domain.StoredMessage

import io.circe.Json

/** 按应用和用户隔离的通知查询、增量同步及阅读水位仓储。 */
trait InboxRepository[F[_]] extends NotificationRepository[F] {

  /** 按 before_seq 向前分页读取通知历史；历史页不推进增量游标。 */
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)]

  /** 按消息身份读取当前用户可见的通知，不向越权请求泄露存在性。 */
  def get(who: Identity, id: String): F[StoredMessage]

  /** 取得当前用户的消息、事件、阅读和留存水位。 */
  def position(who: Identity): F[Position]

  /** 在一致性读取中取得通知快照及其对应水位。 */
  def snapshot(who: Identity): F[(Position, List[StoredMessage])]

  /** 在同一快照中读取通知、会话摘要和水位，另返回会话是否还有后续页。 */
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)]

  /** 串行追赶增量；游标过期时取快照，缓存与游标提交成功后才发布新状态。 */
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)]

  /** 为活跃设备读取指定位置之后的事件，拒绝已退出的设备继续同步。 */
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)]

  /** 单调推进通知阅读水位并返回当前统计，不超过已提交的消息高水位。 */
  def read(who: Identity, seq: Long): F[Position]
}
