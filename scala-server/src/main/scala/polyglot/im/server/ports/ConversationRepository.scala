package polyglot.im.server.ports

import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.StoredMessage

import io.circe.Json

/** 单聊唯一会话、历史、幂等发送和双方已读状态的事务端口。 */
trait ConversationRepository[F[_]] {

  /** 按对方用户身份创建或取得唯一单聊会话。 */
  def createDirect(who: Identity, peer: String): F[Json]

  /** 从 after 位置分页取得当前用户可见的单聊会话摘要。 */
  def conversations(who: Identity, after: String, limit: Int): F[Json]

  /** 按 before 序号读取成员可见的单聊历史，并返回会话状态。 */
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json]

  /** 在同一事务校验成员并幂等写入单聊消息、双方事件、计数和推送任务。 */
  def sendDirect(who: Identity, id: String, data: Json): F[(StoredMessage, Boolean)]

  /** 推进当前成员的单聊阅读位置，并向双方同步已读状态。 */
  def readDirect(who: Identity, id: String, seq: Long): F[Json]
}
