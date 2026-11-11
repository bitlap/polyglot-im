package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

/** 按身份消耗请求配额的端口及装配入口；生产计数存储在 PostgreSQL。 */
trait RateLimiter[F[_]] {

  /** 在共享身份窗口内消耗一次配额，返回是否允许继续执行。 */
  def allow(who: Identity, maximum: Int): F[Boolean]
}
