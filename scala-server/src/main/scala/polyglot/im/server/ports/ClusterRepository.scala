package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

import io.circe.Json

/** 以共享持久状态协调多个服务实例的身份配额。 */
trait ClusterRepository[F[_]] {

  /** 在共享身份窗口内消耗一次配额，返回是否允许继续执行。 */
  def allow(who: Identity, maximum: Int): F[Boolean]
}
