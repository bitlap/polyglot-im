package polyglot.im.transport

import java.net.URI

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.Json

final case class HttpRequest(
    method: String,
    path: String,
    headers: Map[String, String] = Map.empty,
    body: Option[Json] = None
) {
  private val uri = URI.create(path)
  require(path.startsWith("/") && !path.startsWith("//"), "path must be relative to the configured origin")
  require(
    !uri.isAbsolute && uri.getRawAuthority == null && uri.getRawFragment == null && !path.contains("\\"),
    "invalid request path"
  )
}

final case class HttpResponse(status: Int, body: String, headers: Map[String, String] = Map.empty)

/** 网络适配器由宿主提供；核心不创建线程池、HTTP 客户端或全局运行时。 */
trait HttpTransport[F[_]] {
  def execute(request: HttpRequest): F[HttpResponse]
}

trait TokenProvider[F[_]] {
  def token: F[String]
}

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
