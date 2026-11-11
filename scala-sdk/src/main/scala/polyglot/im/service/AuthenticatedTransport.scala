package polyglot.im.service
import cats.MonadThrow
import cats.syntax.all.*
import polyglot.im.ports.*

/** 装饰器借用底层适配器；生命周期由创建适配器的 Resource 管理。 */
final class AuthenticatedTransport[F[_]: MonadThrow](underlying: HttpTransport[F], tokens: TokenProvider[F])
    extends HttpTransport[F] {
  override def execute(request: HttpRequest): F[HttpResponse] =
    tokens.token.flatMap { token =>
      if (token.trim.isEmpty || token.exists(c => c == '\r' || c == '\n'))
        MonadThrow[F].raiseError(new IllegalArgumentException("invalid bearer token"))
      else {
        val headers = request.headers.filterNot { case (key, _) => key.equalsIgnoreCase("Authorization") }
        underlying.execute(request.copy(headers = headers.updated("Authorization", s"Bearer $token")))
      }
    }
}
