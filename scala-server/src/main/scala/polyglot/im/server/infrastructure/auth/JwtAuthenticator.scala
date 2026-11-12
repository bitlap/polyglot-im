package polyglot.im.server.infrastructure.auth

import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol
import polyglot.im.server.ports.{Authenticator, UserAuthenticator}

import java.nio.charset.StandardCharsets.UTF_8
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse

/** HS256 用户/服务密钥分离；也可用受信任的业务鉴权端点验证不透明用户令牌。 */
final class JwtAuthenticator[F[_]: Async](
    userSecret: String,
    serviceSecret: String,
    issuer: String,
    appId: String,
    introspection: Option[(URI, String)] = None,
    http: Option[HttpClient] = None,
    userAuthenticator: Option[UserAuthenticator[F]] = None
) extends Authenticator[F] {
  require(
    (introspection.nonEmpty || userAuthenticator.nonEmpty || userSecret.getBytes(UTF_8).length >= 32) && serviceSecret
      .getBytes(UTF_8)
      .length >= 32,
    "JWT secrets must have at least 32 bytes"
  )
  require(userSecret != serviceSecret, "user and service JWT secrets must differ")
  private val externalUser = userAuthenticator.orElse(introspection.map { case (uri, app) =>
    new HttpSessionAuthenticator[F](
      uri,
      app,
      http.getOrElse(throw new IllegalArgumentException("introspection requires HTTP client"))
    )
  })

  /** 验证凭证签名、有效期及作用域，返回可信身份；失败不接受客户端自报身份。 */
  def authenticate(token: String): F[Identity] =
    if (token.length > 8192 || token.isEmpty) Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "invalid credential"))
    else
      Async[F].delay(verify(token)).handleErrorWith { _ =>
        externalUser match {
          case Some(auth) =>
            auth.authenticateUser(token).flatMap { who =>
              if (who.app == appId && who.role == "user" && who.expires > Protocol.now)
                Async[F].pure(who.copy(types = Set.empty, recipients = Set.empty))
              else Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "invalid user identity"))
            }
          case None => Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "invalid or expired credential"))
        }
      }
  private def verify(token: String): Identity = {
    val parts = token.split("\\.", -1)
    if (parts.length != 3) throw new IllegalArgumentException()
    val header = parse(new String(Protocol.un64(parts(0)), UTF_8)).toOption.get
    if (Protocol.field(header, "alg") != "HS256") throw new IllegalArgumentException()
    val json = parse(new String(Protocol.un64(parts(1)), UTF_8)).toOption.get
    val role = Protocol.field(json, "role")
    if (externalUser.nonEmpty && role != "service") throw new IllegalArgumentException()
    val key =
      if (role == "user") userSecret else if (role == "service") serviceSecret else throw new IllegalArgumentException()
    if (!Protocol.equal(Protocol.hmac(key, parts(0) + "." + parts(1)), parts(2))) throw new IllegalArgumentException()
    val c   = json.hcursor
    val exp = c.get[Long]("exp").toOption.get
    if (
      Protocol.field(json, "app_id") != appId || exp <= Protocol.now || c
        .get[Long]("nbf")
        .toOption
        .exists(_ > Protocol.now + 10) || Protocol
        .field(json, "iss") != issuer || Protocol.field(json, "aud") != "polyglot-im"
    ) throw new IllegalArgumentException()

    /** 读取凭证中的字符串集合，缺失时视为空授权范围。 */
    def set(name: String) = c.get[List[String]](name).getOrElse(Nil).toSet
    Identity(
      Protocol.bounded(Protocol.field(json, "app_id"), "app_id"),
      Protocol.bounded(Protocol.field(json, "sub"), "sub"),
      role,
      exp,
      set("types"),
      set("recipients")
    )
  }
}

/** 创建支持本地 JWT 与可选宿主内省的受管理鉴权资源。 */
object JwtAuthenticator {

  /** 创建 JWT/身份内省鉴权资源，并在退出时关闭自有 HTTP 连接资源。 */
  def resource[F[_]: Async](
      userSecret: String,
      serviceSecret: String,
      issuer: String,
      appId: String,
      introspection: Option[(URI, String)]
  ): Resource[F, Authenticator[F]] = {
    val httpResource: Resource[F, Option[HttpClient]] =
      if (introspection.isEmpty) Resource.pure(None)
      else
        for {
          executor <- Resource.make(Async[F].delay(java.util.concurrent.Executors.newFixedThreadPool(4)))(e =>
            Async[F].blocking { e.shutdown(); e.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); () }
          )
          client <- Resource.eval(
            Async[F].delay(
              HttpClient
                .newBuilder()
                .executor(executor)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
            )
          )
        } yield Some(client)
    httpResource.evalMap(http =>
      Async[F].delay(new JwtAuthenticator[F](userSecret, serviceSecret, issuer, appId, introspection, http))
    )
  }
}
