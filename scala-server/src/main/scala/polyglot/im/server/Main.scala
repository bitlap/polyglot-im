package polyglot.im.server

import polyglot.im.server.api.http.Server
import polyglot.im.server.config.AppConfig

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import fs2.io.net.Network

/** 读取服务配置并运行受资源作用域管理的 IM 服务。 */
object Main extends IOApp {
  private given Network[IO] = Network.forAsync[IO]

  /** 装配并初始化仓储、鉴权、HTTP/WS 与后台任务，退出时统一取消并释放。 */
  def resource(config: AppConfig): Resource[IO, org.http4s.server.Server] =
    resource(config, polyglot.im.server.bootstrap.ServerComponents.postgres[IO](config))

  /** 装配并初始化仓储、鉴权、HTTP/WS 与后台任务，退出时统一取消并释放。 */
  def resource(
      config: AppConfig,
      components: polyglot.im.server.bootstrap.ServerComponents[IO]
  ): Resource[IO, org.http4s.server.Server] =
    polyglot.im.server.bootstrap.ServerApplication.resource[IO](config, components)

  /** 运行入口任务直到完成或取消，资源随作用域退出而释放。 */
  def run(args: List[String]): IO[ExitCode] = for {
    config <- IO
      .blocking(AppConfig.location(args).flatMap(path => AppConfig.load(path)))
      .flatMap(result => IO.fromEither(result.left.map(new IllegalArgumentException(_))))
    result <- resource(config).useForever.as(ExitCode.Success)
  } yield result
}
