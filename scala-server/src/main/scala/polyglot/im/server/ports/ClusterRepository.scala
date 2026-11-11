package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

import io.circe.Json

trait ClusterRepository[F[_]] {
  def allow(who: Identity, maximum: Int): F[Boolean]
}
