package polyglot.im.server.api.http

import polyglot.im.server.config.ServerConfig
import polyglot.im.server.domain.ApiError
import polyglot.im.server.ports.Readiness

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.circe.Json
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.server.Router

import polyglot.im.server.api.http.ApiResponse

object Server {
  val apiPrefix: String = "/api/v1/im"

  /** 只在 http4s Router 挂载公共前缀，业务路由匹配相对路径。 */
  def apiRoutes[F[_]: Async](routes: HttpRoutes[F]): HttpRoutes[F] = Router(apiPrefix -> routes)

  def routes[F[_]: Async](readiness: Readiness[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "health" / "live" =>
        Ok(ApiResponse.success(Json.obj("status" -> Json.fromString("alive"), "stage" -> Json.fromString("running"))))
      case GET -> Root / "health" / "ready" =>
        readiness.check.attempt.flatMap {
          case Right(true) => Ok(ApiResponse.success(Json.obj("status" -> Json.fromString("ready"))))
          case _ =>
            ServiceUnavailable(
              ApiResponse.failure(ApiError(503, "NOT_READY", "IM persistence is not configured"))
            )
        }
    }
  }

  /** 健康探针先匹配；业务路由由组合根显式注入，未匹配返回统一 JSON 404。 */
  def app[F[_]: Async](readiness: Readiness[F], extraRoutes: HttpRoutes[F]): HttpApp[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    val fallback = HttpRoutes.of[F] { case _ =>
      NotFound(ApiResponse.failure(ApiError(404, "NOT_FOUND", "Route not implemented")))
    }
    (routes(readiness) <+> apiRoutes(extraRoutes) <+> fallback).orNotFound
  }

  def resource[F[_]: Async: Network](
      config: ServerConfig,
      readiness: Readiness[F],
      extraRoutes: HttpRoutes[F]
  ): Resource[F, org.http4s.server.Server] =
    EmberServerBuilder
      .default[F]
      .withHost(config.host)
      .withPort(config.port)
      .withHttpApp(app(readiness, extraRoutes))
      .build
  def websocketResource[F[_]: Async: Network](
      config: ServerConfig,
      app: WebSocketBuilder2[F] => HttpApp[F]
  ): Resource[F, org.http4s.server.Server] =
    EmberServerBuilder
      .default[F]
      .withHost(config.host)
      .withPort(config.port)
      .withShutdownTimeout(scala.concurrent.duration.DurationInt(5).seconds)
      .withHttpWebSocketApp(app)
      .build

}
