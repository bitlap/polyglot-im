package polyglot.im.server

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import fs2.io.net.Network

object Main extends IOApp {
  private given Network[IO] = Network.forAsync[IO]

  def resource(config: AppConfig): Resource[IO, org.http4s.server.Server] = for {
    store <- Store.resource[IO](config.database)
    _     <- Resource.eval(store.migrate)
    auth <- JwtAuthenticator.resource[IO](
      config.auth.userJwtSecret,
      config.auth.serviceJwtSecret,
      config.auth.issuer,
      config.auth.appId,
      config.introspection.map(uri => (uri, config.auth.appId))
    )
    limits <- Resource.eval(Ref.of[IO, Map[String, (Long, Int)]](Map.empty))
    api   = new Api[IO](store, auth, new CursorCodec(config.auth.cursorSecret), limits, config.rateLimits)
    ready = new Readiness[IO] { def check: IO[Boolean] = store.ready }
    provider <- config.apns.fold(Resource.pure[IO, Option[PushProvider[IO]]](None))(c =>
      ApnsProvider.resource[IO](c).map(Some(_))
    )
    _      <- provider.fold(Resource.unit[IO])(p => Resource.make(PushWorker.run(store, p).start)(_.cancel).void)
    server <- Server.websocketResource[IO](config.server, ws => Server.app[IO](ready, api.routes(ws)))
    _ <- Resource.eval(IO.println(s"polyglot-im listening on ${server.address}; APNs enabled=${provider.nonEmpty}"))
  } yield server

  def run(args: List[String]): IO[ExitCode] = for {
    config <- IO
      .blocking(AppConfig.location(args).flatMap(path => AppConfig.load(path)))
      .flatMap(result => IO.fromEither(result.left.map(new IllegalArgumentException(_))))
    result <- resource(config).useForever.as(ExitCode.Success)
  } yield result
}
