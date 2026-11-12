package polyglot.im.server.infrastructure.auth

import polyglot.im.server.domain.{ApiError, Identity, Protocol}
import polyglot.im.server.ports.UserAuthenticator
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletionStage, Flow}
import java.time.{Duration, Instant}
import cats.effect.Async
import cats.syntax.all.*
import io.circe.parser.parse

/** 默认不透明 Bearer 适配器；固定可信端点兼容 App 和小程序共用的业务会话。 */
final class HttpSessionAuthenticator[F[_]: Async](uri: URI, appId: String, http: HttpClient)
    extends UserAuthenticator[F] {
  require(
    uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null && uri.getQuery == null,
    "invalid introspection URL"
  )
  require(
    uri.getScheme == "https" || (uri.getScheme == "http" && Set("127.0.0.1", "localhost", "::1")(uri.getHost)),
    "introspection requires HTTPS or loopback"
  )
  require(http.followRedirects() == HttpClient.Redirect.NEVER, "introspection must not follow redirects")

  /** 每次向宿主验证，不保留令牌缓存；失败关闭，不从调用方接受用户 ID 或权限范围。 */
  def authenticateUser(token: String): F[Identity] = {
    val invalid = ApiError(401, "UNAUTHORIZED", "invalid or expired business session")
    if (token.isEmpty || token.length > 8192 || token.exists(c => c <= ' ' || c == 127))
      Async[F].raiseError(invalid)
    else
      Async[F]
        .delay(
          HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET()
            .build()
        )
        .flatMap(request =>
          Async[F]
            .fromCompletableFuture(
              Async[F].delay(
                http.sendAsync(
                  request,
                  _ => {
                    val target = HttpResponse.BodySubscribers.ofByteArray()
                    new HttpResponse.BodySubscriber[Array[Byte]] {
                      private var subscription: Flow.Subscription = null
                      private var size                            = 0L
                      def getBody(): CompletionStage[Array[Byte]] = target.getBody()
                      def onSubscribe(value: Flow.Subscription): Unit = {
                        subscription = value; target.onSubscribe(value)
                      }
                      def onNext(chunks: java.util.List[ByteBuffer]): Unit = {
                        val iterator = chunks.iterator()
                        while (iterator.hasNext) size += iterator.next().remaining()
                        if (size > 16384) { subscription.cancel(); target.onError(invalid) }
                        else target.onNext(chunks)
                      }
                      def onError(error: Throwable): Unit = target.onError(error)
                      def onComplete(): Unit              = target.onComplete()
                    }
                  }
                )
              )
            )
        )
        .flatMap { response =>
          Async[F].blocking {
            val bytes = response.body()
            if (response.statusCode() != 200 || bytes.length > 16384) throw invalid
            val json =
              parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).toOption.getOrElse(throw invalid)
            val data = json.hcursor.downField("data").focus.getOrElse(json).hcursor
            if (!data.get[Boolean]("active").getOrElse(false) || data.get[String]("app_id").getOrElse("") != appId)
              throw invalid
            val user = Protocol.bounded(data.get[String]("user_id").getOrElse(throw invalid), "user_id")
            val expiry = data
              .get[Long]("exp")
              .toOption
              .orElse(
                data
                  .get[String]("expires_at")
                  .toOption
                  .flatMap(value => scala.util.Try(Instant.parse(value).getEpochSecond).toOption)
              )
              .getOrElse(throw invalid)
            if (expiry <= Protocol.now) throw invalid
            Identity(appId, user, "user", math.min(expiry, Protocol.now + 300))
          }
        }
        .handleErrorWith(_ => Async[F].raiseError(invalid))
  }
}
