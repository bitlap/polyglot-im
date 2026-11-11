package polyglot.im.ports

import java.net.URI

import io.circe.Json

/** 相对配置源的 HTTP 请求；拒绝绝对 URL、跨源路径和非法控制字符。 */
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

/** 传输层 HTTP 响应，保留真实状态码、响应正文和响应头。 */
final case class HttpResponse(status: Int, body: String, headers: Map[String, String] = Map.empty)

/** 网络适配器由宿主提供；核心不创建线程池、HTTP 客户端或全局运行时。 */
trait HttpTransport[F[_]] {

  /** 执行一次传输并保留真实 HTTP 状态；不在此层推断消息送达或已读。 */
  def execute(request: HttpRequest): F[HttpResponse]
}

/** 每次请求读取有效登录凭证的异步端口。 */
trait TokenProvider[F[_]] {

  /** 读取本次请求使用的登录令牌，不将令牌持久化到消息缓存。 */
  def token: F[String]
}
