package polyglot.im.ports

import java.net.URI

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
