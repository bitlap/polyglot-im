package polyglot.im.server

import java.net.URI
import java.nio.file.Path
import cats.effect.{IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import fs2.io.net.Network

object Main extends IOApp.Simple {
  private given Network[IO] = Network.forAsync[IO]
  private def required(env: Map[String, String], name: String): String =
    env.get(name).filter(_.nonEmpty).getOrElse(throw new IllegalArgumentException(s"missing $name"))
  val run: IO[Unit] = IO.delay(sys.env).flatMap { env =>
    val resources = for {
      config <- Resource.eval(
        IO.fromEither(ServerConfig.fromEnvironment(env).left.map(new IllegalArgumentException(_)))
      )
      database <- Resource.eval(
        IO.fromEither(DatabaseConfig.fromEnvironment(env).left.map(new IllegalArgumentException(_)))
      )
      store <- Store.resource[IO](database)
      _     <- Resource.eval(store.migrate)
      app           = required(env, "POLYGLOT_IM_APP_ID")
      introspection = env.get("POLYGLOT_IM_INTROSPECTION_URL").map(u => (URI.create(u), app))
      auth <- JwtAuthenticator.resource[IO](
        required(env, "POLYGLOT_IM_USER_JWT_SECRET"),
        required(env, "POLYGLOT_IM_SERVICE_JWT_SECRET"),
        env.getOrElse("POLYGLOT_IM_JWT_ISSUER", "polyglot-im"),
        app,
        introspection
      )
      codec = new CursorCodec(required(env, "POLYGLOT_IM_CURSOR_SECRET"))
      limits <- Resource.eval(Ref.of[IO, Map[String, (Long, Int)]](Map.empty))
      api       = new Api[IO](store, auth, codec, limits)
      readiness = new Readiness[IO] { def check: IO[Boolean] = store.ready }
      provider <-
        if (env.get("POLYGLOT_IM_APNS_ENABLED").contains("true"))
          ApnsProvider
            .resource[IO](
              ApnsConfig(
                required(env, "POLYGLOT_IM_APNS_TEAM_ID"),
                required(env, "POLYGLOT_IM_APNS_KEY_ID"),
                required(env, "POLYGLOT_IM_APNS_TOPIC"),
                Path.of(required(env, "POLYGLOT_IM_APNS_KEY_FILE")),
                env.get("POLYGLOT_IM_APNS_SANDBOX").contains("true")
              )
            )
            .map(Some(_))
        else Resource.pure[IO, Option[PushProvider[IO]]](None)
      _      <- provider.fold(Resource.unit[IO])(p => Resource.make(PushWorker.run(store, p).start)(_.cancel).void)
      server <- Server.websocketResource[IO](config, ws => Server.app[IO](readiness, api.routes(ws)))
      _ <- Resource.eval(IO.println(s"polyglot-im listening on ${server.address}; APNs enabled=${provider.nonEmpty}"))
    } yield server
    resources.useForever
  }
}
