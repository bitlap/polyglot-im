package polyglot.im.server.bootstrap
import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import fs2.io.net.Network
import scala.concurrent.duration.*
import polyglot.im.server.config.AppConfig
import polyglot.im.server.api.http.{Api, Server, RequestGate}
import polyglot.im.server.security.CursorCodec
import polyglot.im.server.ports.Readiness
import polyglot.im.server.service.{RateLimiter, PushWorker}

/** 装配数据库、路由和后台 Worker，关闭时统一取消任务并释放资源。 */
object ServerApplication {

  /** 装配并初始化仓储、鉴权、HTTP/WS 与后台任务，退出时统一取消并释放。 */
  def resource[F[_]: Async: Network](
      config: AppConfig,
      components: ServerComponents[F]
  ): Resource[F, org.http4s.server.Server] = for {
    store   <- components.repository
    _       <- Resource.eval(store.initialize)
    auth    <- components.authenticator
    changes <- components.changes
    _ <- Resource.make(
      Stream
        .awakeEvery[F](1.minute)
        .evalMap(_ =>
          store.maintain.handleErrorWith(_ =>
            Async[F].delay(
              org.slf4j.LoggerFactory.getLogger("polyglot.im.maintenance").warn("Maintenance failed; retrying")
            )
          )
        )
        .compile
        .drain
        .start
    )(_.cancel)
    api = new Api[F](
      store,
      auth,
      new CursorCodec(config.auth.cursorSecret),
      RateLimiter.postgres(store),
      config.rateLimits,
      changes
    )
    ready = new Readiness[F] {

      /** 查询已装配仓储的当前就绪状态，不新建独立数据库连接池。 */
      def check: F[Boolean] = store.ready
    }
    provider <- components.push
    _ <- provider.fold(Resource.unit[F])(p => Resource.make(PushWorker.run(store, p, config.push).start)(_.cancel).void)
    gate   <- RequestGate.resource[F](config.maxInFlight)
    server <- Server.websocketResource[F](config.server, ws => gate(Server.app[F](ready, api.routes(ws))))
    _ <- Resource.eval(
      Async[F].delay(
        org.slf4j.LoggerFactory
          .getLogger("polyglot.im.server")
          .info(s"polyglot-im listening on ${server.address}; APNs enabled=${provider.nonEmpty}")
      )
    )
  } yield server
}
