package polyglot.im.server

import polyglot.im.server.config.AppSettings

import cats.effect.{IO, Resource}
import polyglot.im.server.bootstrap.ServerComponents
import polyglot.im.server.ports.Authenticator

object ServerWiring {
  def withAuthenticator(
      config: AppSettings,
      auth: Resource[IO, Authenticator[IO]]
  ): Resource[IO, org.http4s.server.Server] =
    Main.resource(config, ServerComponents.postgres[IO](config).copy(authenticator = auth))
}
