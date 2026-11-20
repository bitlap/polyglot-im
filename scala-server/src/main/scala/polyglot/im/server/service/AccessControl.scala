package polyglot.im.server.service
import cats.effect.Async
import cats.syntax.all.*
import polyglot.im.server.config.RateLimitSettings
import polyglot.im.server.domain.{Identity, ApiError}
import polyglot.im.server.ports.{Authenticator, RateLimiter}

import polyglot.im.server.domain.ApiError.{Code, Message}

/** 组合令牌认证、预期身份校验、角色限制及跨实例配额检查。 */
final class AccessControl[F[_]: Async](auth: Authenticator[F], limiter: RateLimiter[F], limits: RateLimitSettings) {

  /** 校验凭证、角色、预期应用/用户和身份配额，全部通过后返回身份。 */
  def authorize(token: String, role: String, expectedApp: Option[String], expectedUser: Option[String]): F[Identity] =
    for {
      who <- auth.authenticate(token)
      _ <-
        if (expectedApp.exists(_ != who.app) || expectedUser.exists(_ != who.user))
          Async[F].raiseError(ApiError(403, Code.IdentityMismatch, Message.IdentityMismatch))
        else Async[F].unit
      _ <-
        if (who.role == role) Async[F].unit
        else Async[F].raiseError(ApiError(403, Code.Forbidden, Message.RoleNotPermitted))
      accepted <- limiter.allow(who, if (role == "service") limits.servicePerMinute else limits.userPerMinute)
      _ <- if (accepted) Async[F].unit else Async[F].raiseError(ApiError(429, Code.RateLimited, Message.RateLimited))
    } yield who
}
