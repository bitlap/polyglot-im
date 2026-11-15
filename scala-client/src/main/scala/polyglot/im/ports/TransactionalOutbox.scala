package polyglot.im.ports

import polyglot.im.domain.NotificationIntent

/** 业务事务入队端口；Transaction 由宿主及具体适配器约定，核心不依赖数据库驱动。 */
trait TransactionalOutbox[F[_], Transaction] extends Outbox[F] {

  /** 在调用方已开启的事务内写入幂等通知意图；不提交、回滚或关闭调用方事务。 */
  def enqueue(transaction: Transaction, event: NotificationIntent): F[Unit]
}
