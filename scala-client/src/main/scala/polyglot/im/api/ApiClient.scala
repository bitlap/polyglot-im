package polyglot.im.api

import java.nio.charset.StandardCharsets.UTF_8
import java.net.URLEncoder
import scala.concurrent.duration.*
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import polyglot.im.domain.{AccountScope, ImError}
import polyglot.im.ports.{HttpTransport, HttpRequest, RemoteApi, WireCodec}

/** HTTP API 公共路径前缀定义；请求行为由 ApiClient 实例执行。 */
object ApiClient {
  val apiPrefix: String = polyglot.im.config.ProtocolConfig.apiPrefix
}

/** 调用 IM HTTP API，统一处理身份绑定、响应信封、认证刷新和有界重试。 */
final class ApiClient[F[_]: Async](
    transport: HttpTransport[F],
    refresh: Option[F[Unit]] = None,
    expectedScope: Option[AccountScope] = None,
    codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec,
    retry: polyglot.im.config.RetryPolicy = polyglot.im.config.RetryPolicy()
) extends RemoteApi[F] {

  /** 创建绑定指定账号作用域的请求入口，保留注入的传输和编解码器。 */
  def scoped(scope: AccountScope): ApiClient[F] = new ApiClient(transport, refresh, Some(scope), codec, retry)

  /** 请求相对 IM API 路径并返回解包后的对象；临时错误有限重试，认证刷新最多一次。 */
  def request(method: String, path: String, body: Option[Json] = None): F[Json] = {

    /** 在当前操作范围内继续处理后续页或下一次连接尝试。 */
    def loop(attempt: Int, refreshed: Boolean): F[Json] =
      transport
        .execute(
          HttpRequest(
            method,
            ApiClient.apiPrefix + path,
            headers = expectedScope.fold(Map.empty[String, String])(s =>
              Map(
                "X-IM-App-ID"  -> URLEncoder.encode(s.appId, UTF_8),
                "X-IM-User-ID" -> URLEncoder.encode(s.userId, UTF_8)
              )
            ),
            body = body
          )
        )
        .attempt
        .flatMap {
          case Left(error: java.io.IOException) if attempt < retry.maxRetries =>
            Async[F].sleep((retry.initialDelay.toMillis * (1L << attempt)).millis) *> loop(attempt + 1, refreshed)
          case Left(error) => Async[F].raiseError(error)
          case Right(response) =>
            (if (response.status >= 400) Async[F].pure(codec.decode(response.body).getOrElse(Json.obj()))
             else Async[F].fromEither(codec.decode(response.body))).flatMap { data =>
              if (response.status >= 200 && response.status < 300) {
                data.hcursor.get[Int]("code").toOption match {
                  case Some(200) if data.hcursor.downField("data").focus.exists(_.isObject) =>
                    Async[F].pure(data.hcursor.downField("data").focus.get)
                  case Some(_) => Async[F].raiseError(new IllegalArgumentException("invalid API response envelope"))
                  case None    => Async[F].pure(data)
                }
              } else if (response.status == 401 && refresh.nonEmpty && !refreshed) refresh.get *> loop(attempt, true)
              else if (Set(429, 500, 502, 503, 504)(response.status) && attempt < retry.maxRetries) {
                val delay = response.headers
                  .get("retry-after")
                  .flatMap(_.toLongOption)
                  .getOrElse(0L)
                  .min(retry.retryAfterLimit.toSeconds)
                  .max(0)
                Async[F].sleep((retry.initialDelay.toMillis * (1L << attempt)).millis.max(delay.seconds)) *> loop(
                  attempt + 1,
                  refreshed
                )
              } else
                Async[F].raiseError(
                  ImError(
                    response.status,
                    data.hcursor
                      .get[String]("error_code")
                      .orElse(data.hcursor.get[String]("code"))
                      .getOrElse("HTTP_ERROR"),
                    data.hcursor.get[String]("message").getOrElse("request failed")
                  )
                )
            }
        }
    loop(0, false)
  }
}
