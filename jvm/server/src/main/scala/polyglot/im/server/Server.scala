package polyglot.im.server

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.io.net.Network
import io.circe.Json
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.server.EmberServerBuilder

final case class ServerConfig(host: Host, port: Port)

object ServerConfig {
  def fromEnvironment(env: Map[String, String]): Either[String, ServerConfig] =
    for {
      host       <- Host.fromString(env.getOrElse("POLYGLOT_IM_HOST", "127.0.0.1")).toRight("invalid POLYGLOT_IM_HOST")
      portNumber <- env.getOrElse("POLYGLOT_IM_PORT", "8090").toIntOption.toRight("invalid POLYGLOT_IM_PORT")
      port       <- Port.fromInt(portNumber).toRight("invalid POLYGLOT_IM_PORT")
    } yield ServerConfig(host, port)
}

/** 就绪检查可替换为真实持久化依赖检查；初始化阶段固定未就绪。 */
trait Readiness[F[_]] {
  def check: F[Boolean]
}

object Server {
  def routes[F[_]: Async](readiness: Readiness[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "health" / "live" =>
        Ok(Json.obj("status" -> Json.fromString("alive"), "stage" -> Json.fromString("bootstrap")))
      case GET -> Root / "health" / "ready" =>
        readiness.check.attempt.flatMap {
          case Right(true) => Ok(Json.obj("status" -> Json.fromString("ready")))
          case _ =>
            ServiceUnavailable(
              Json.obj(
                "code"    -> Json.fromString("NOT_READY"),
                "message" -> Json.fromString("IM persistence is not configured")
              )
            )
        }
    }
  }

  /** 健康探针先匹配；业务路由由组合根显式注入，未匹配返回统一 JSON 404。 */
  def app[F[_]: Async](readiness: Readiness[F], extraRoutes: HttpRoutes[F]): HttpApp[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    val fallback = HttpRoutes.of[F] { case _ =>
      NotFound(Json.obj("code" -> Json.fromString("NOT_FOUND"), "message" -> Json.fromString("Route not implemented")))
    }
    (routes(readiness) <+> extraRoutes <+> fallback).orNotFound
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
}
