package polyglot.im.ports

trait RemoteApi[F[_]] {
  def scoped(scope: polyglot.im.domain.AccountScope): RemoteApi[F]
  def request(method: String, path: String, body: Option[io.circe.Json] = None): F[io.circe.Json]
}
