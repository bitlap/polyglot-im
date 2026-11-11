package polyglot.im.server

import cats.effect.{IO, Resource}
import polyglot.im.server.bootstrap.ServerComponents
import polyglot.im.server.ports.Authenticator

object ServerWiring {
  def withAuthenticator(
      config: AppConfig,
      auth: Resource[IO, Authenticator[IO]]
  ): Resource[IO, org.http4s.server.Server] =
    Main.resource(config, ServerComponents.postgres[IO](config).copy(authenticator = auth))
}
