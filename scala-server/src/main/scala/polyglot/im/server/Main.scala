package polyglot.im.server

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import fs2.io.net.Network
import scala.concurrent.duration.*
import fs2.Stream

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
    changes <- ChangeFeed.postgres[IO](config.database)
    _ <- Resource.make(
      Stream
        .awakeEvery[IO](1.minute)
        .evalMap(_ =>
          store.maintain.handleErrorWith(_ =>
            IO.delay(org.slf4j.LoggerFactory.getLogger("polyglot.im.maintenance").warn("Maintenance failed; retrying"))
          )
        )
        .compile
        .drain
        .start
    )(_.cancel)
    api = new Api[IO](
      store,
      auth,
      new CursorCodec(config.auth.cursorSecret),
      RateLimiter.postgres(store),
      config.rateLimits,
      Some(changes)
    )
    ready = new Readiness[IO] { def check: IO[Boolean] = store.ready }
    provider <- config.apns.fold(Resource.pure[IO, Option[PushProvider[IO]]](None))(c =>
      ApnsProvider.resource[IO](c).map(Some(_))
    )
    _ <- provider.fold(Resource.unit[IO])(p =>
      Resource.make(PushWorker.run(store, p, config.push).start)(_.cancel).void
    )
    gate   <- RequestGate.resource[IO](config.maxInFlight)
    server <- Server.websocketResource[IO](config.server, ws => gate(Server.app[IO](ready, api.routes(ws))))
    _ <- Resource.eval(IO.println(s"polyglot-im listening on ${server.address}; APNs enabled=${provider.nonEmpty}"))
  } yield server

  def run(args: List[String]): IO[ExitCode] = for {
    config <- IO
      .blocking(AppConfig.location(args).flatMap(path => AppConfig.load(path)))
      .flatMap(result => IO.fromEither(result.left.map(new IllegalArgumentException(_))))
    result <- resource(config).useForever.as(ExitCode.Success)
  } yield result
}
