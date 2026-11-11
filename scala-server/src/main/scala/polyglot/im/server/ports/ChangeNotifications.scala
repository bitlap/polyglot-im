package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

/** 提示某个用户可能有新事件；提示可以重复，可靠数据仍从仓储读取。 */
trait ChangeNotifications[F[_]] {

  /** 读取变化通知连接当前是否可用；不可用时可靠补拉仍独立工作。 */
  def connected: F[Boolean]

  /** Wake-up hints, never the source of truth. Emit immediately on subscription and reconcile at least every five
    * seconds even when the notification backend is unavailable. Consumers reload durable events after every hint;
    * duplicate hints are safe.
    */
  def subscribe(who: Identity): cats.effect.Resource[F, fs2.Stream[F, Unit]]
}
