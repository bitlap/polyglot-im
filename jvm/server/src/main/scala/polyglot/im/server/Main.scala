package polyglot.im.server

import cats.effect.{IO, IOApp}
import fs2.io.net.Network
import org.http4s.HttpRoutes

/** IO 只在组合根落地；Resource 负责 HTTP 服务的启动、取消与释放。 */
object Main extends IOApp.Simple {
  private given Network[IO] = Network.forAsync[IO]

  override val run: IO[Unit] =
    for {
      env    <- IO.delay(sys.env)
      config <- IO.fromEither(ServerConfig.fromEnvironment(env).left.map(new IllegalArgumentException(_)))
      readiness = new Readiness[IO] {
        override def check: IO[Boolean] = IO.pure(false)
      }
      _ <- Server.resource[IO](config, readiness, HttpRoutes.empty[IO]).useForever
    } yield ()
}
