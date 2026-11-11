package polyglot.im.server.service
import cats.effect.Async
import cats.syntax.all.*
import polyglot.im.server.config.RateLimits
import polyglot.im.server.domain.{Identity, ApiError}
import polyglot.im.server.ports.{Authenticator, RateLimiter}
final class AccessControl[F[_]: Async](auth: Authenticator[F], limiter: RateLimiter[F], limits: RateLimits) {
  def authorize(token: String, role: String, expectedApp: Option[String], expectedUser: Option[String]): F[Identity] =
    for {
      who <- auth.authenticate(token)
      _ <-
        if (expectedApp.exists(_ != who.app) || expectedUser.exists(_ != who.user))
          Async[F].raiseError(ApiError(403, "IDENTITY_MISMATCH", "credential differs from expected account"))
        else Async[F].unit
      _ <-
        if (who.role == role) Async[F].unit
        else Async[F].raiseError(ApiError(403, "FORBIDDEN", "credential role is not permitted"))
      accepted <- limiter.allow(who, if (role == "service") limits.servicePerMinute else limits.userPerMinute)
      _ <- if (accepted) Async[F].unit else Async[F].raiseError(ApiError(429, "RATE_LIMITED", "retry after one minute"))
    } yield who
}
