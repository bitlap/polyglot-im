package polyglot.im.server

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import fs2.io.net.Network

object Main extends IOApp {
  private given Network[IO] = Network.forAsync[IO]

  def resource(config: AppConfig): Resource[IO, org.http4s.server.Server] =
    resource(config, polyglot.im.server.bootstrap.ServerComponents.postgres[IO](config))

  def resource(
      config: AppConfig,
      components: polyglot.im.server.bootstrap.ServerComponents[IO]
  ): Resource[IO, org.http4s.server.Server] =
    polyglot.im.server.bootstrap.ServerApplication.resource[IO](config, components)

  def run(args: List[String]): IO[ExitCode] = for {
    config <- IO
      .blocking(AppConfig.location(args).flatMap(path => AppConfig.load(path)))
      .flatMap(result => IO.fromEither(result.left.map(new IllegalArgumentException(_))))
    result <- resource(config).useForever.as(ExitCode.Success)
  } yield result
}
