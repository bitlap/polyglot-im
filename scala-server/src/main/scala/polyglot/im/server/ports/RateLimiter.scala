package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

trait RateLimiter[F[_]] {
  def allow(who: Identity, maximum: Int): F[Boolean]
}
