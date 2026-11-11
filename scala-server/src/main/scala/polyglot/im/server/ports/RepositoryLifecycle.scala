package polyglot.im.server.ports

import io.circe.Json

trait RepositoryLifecycle[F[_]] {
  def migrate: F[Unit]
  def ready: F[Boolean]
  def maintain: F[Unit]
  def purgeEvents(beforeEpochSeconds: Long): F[Unit]
}
