package polyglot.im.server.bootstrap
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import polyglot.im.server.config.AppConfig
import polyglot.im.server.ports.*
import polyglot.im.server.infrastructure.postgres.{PostgresStore, ChangeFeed}
import polyglot.im.server.infrastructure.auth.JwtAuthenticator
import polyglot.im.server.infrastructure.push.ApnsProvider

/** 组合根显式持有适配器资源；替换后端不修改业务用例与 HTTP 路由。 */
final case class ServerComponents[F[_]](
    repository: Resource[F, ImRepository[F]],
    authenticator: Resource[F, Authenticator[F]],
    changes: Resource[F, Option[ChangeNotifications[F]]],
    push: Resource[F, Option[PushProvider[F]]]
)
object ServerComponents {
  def postgres[F[_]: Async](config: AppConfig): ServerComponents[F] = ServerComponents(
    PostgresStore.resource[F](config.database).map(s => s: ImRepository[F]),
    JwtAuthenticator
      .resource[F](
        config.auth.userJwtSecret,
        config.auth.serviceJwtSecret,
        config.auth.issuer,
        config.auth.appId,
        config.introspection.map(uri => (uri, config.auth.appId))
      )
      .map(a => a: Authenticator[F]),
    ChangeFeed.postgres[F](config.database).map(c => Some(c: ChangeNotifications[F])),
    config.apns.fold(Resource.pure[F, Option[PushProvider[F]]](None))(c => ApnsProvider.resource[F](c).map(Some(_)))
  )
}
