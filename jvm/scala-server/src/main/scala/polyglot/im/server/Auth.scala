package polyglot.im.server

import java.nio.charset.StandardCharsets.UTF_8
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse

trait Authenticator[F[_]] { def authenticate(token: String): F[Identity] }

/** HS256 用户/服务密钥分离；也可用受信任的业务鉴权端点验证不透明用户令牌。 */
final class JwtAuthenticator[F[_]: Async](
    userSecret: String,
    serviceSecret: String,
    issuer: String,
    appId: String,
    introspection: Option[(URI, String)] = None,
    http: Option[HttpClient] = None
) extends Authenticator[F] {
  require(
    userSecret.getBytes(UTF_8).length >= 32 && serviceSecret.getBytes(UTF_8).length >= 32,
    "JWT secrets must have at least 32 bytes"
  )
  require(userSecret != serviceSecret, "user and service JWT secrets must differ")
  require(introspection.isEmpty || http.nonEmpty, "introspection requires a managed HTTP client")
  introspection.foreach { case (uri, _) =>
    require(
      uri.getScheme == "https" || (uri.getScheme == "http" && Set("127.0.0.1", "localhost", "::1")(uri.getHost)),
      "introspection requires HTTPS or loopback"
    )
    require(uri.getUserInfo == null && uri.getFragment == null, "invalid introspection URL")
  }
  def authenticate(token: String): F[Identity] =
    if (token.length > 8192 || token.isEmpty) Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "invalid credential"))
    else
      Async[F].delay(verify(token)).handleErrorWith { _ =>
        introspection match {
          case None => Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "invalid or expired credential"))
          case Some((uri, app)) =>
            val request = HttpRequest
              .newBuilder(uri)
              .timeout(Duration.ofSeconds(5))
              .header("Authorization", "Bearer " + token)
              .GET()
              .build()
            Async[F]
              .fromCompletableFuture(Async[F].delay(http.get.sendAsync(request, HttpResponse.BodyHandlers.ofString())))
              .flatMap { response =>
                Async[F].delay {
                  if (response.statusCode() != 200) throw ApiError(401, "UNAUTHORIZED", "business login is invalid")
                  val json = parse(response.body()).toOption
                    .getOrElse(throw ApiError(401, "UNAUTHORIZED", "invalid identity response"))
                  val identity = json.hcursor.downField("data").focus.getOrElse(json)
                  val user     = Protocol.bounded(Protocol.field(identity, "user_id"), "user_id")
                  if (
                    identity.hcursor
                      .get[String]("app_id")
                      .toOption
                      .exists(_ != app) || identity.hcursor.get[Boolean]("active").toOption.contains(false)
                  )
                    throw ApiError(401, "UNAUTHORIZED", "business login is inactive")
                  Identity(app, user, "user", Protocol.now + 300)
                }
              }
              .handleErrorWith(_ => Async[F].raiseError(ApiError(401, "UNAUTHORIZED", "identity verification failed")))
        }
      }
  private def verify(token: String): Identity = {
    val parts = token.split("\\.", -1)
    if (parts.length != 3) throw new IllegalArgumentException()
    val header = parse(new String(Protocol.un64(parts(0)), UTF_8)).toOption.get
    if (Protocol.field(header, "alg") != "HS256") throw new IllegalArgumentException()
    val json = parse(new String(Protocol.un64(parts(1)), UTF_8)).toOption.get
    val role = Protocol.field(json, "role")
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

object JwtAuthenticator {
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
