package polyglot.im.ports

import polyglot.im.domain.{OutboxClaim, OutboxCompletion}

/** 派发所需的持久化端口；实现负责原子领取、数据库时钟与租约围栏，不负责网络或重试分类。 */
trait Outbox[F[_]] {

  /** 原子领取一条到期事件并提交租约；无可用记录返回 None，leaseSeconds 必须为正。 */
  def claim(leaseSeconds: Long): F[Option[OutboxClaim]]

  /** 按事件 ID 与原租约原子确认结果并增加尝试次数；租约已被替换时返回 false，不改动记录。 */
  def complete(row: OutboxClaim, result: OutboxCompletion): F[Boolean]
}
