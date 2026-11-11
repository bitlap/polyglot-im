package polyglot.im.ports
import io.circe.Json
import polyglot.im.domain.AccountScope

trait LocalStore[F[_]] {
  def load(scope: AccountScope): F[Json]
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json]
  def clear(scope: AccountScope): F[Unit]
}
