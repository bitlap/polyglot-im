package polyglot.im.server

import cats.data.Kleisli
import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.*

/** 限制正在处理的 HTTP 请求；满载立即拒绝，避免等待数据库连接的无界排队。 */
final class RequestGate[F[_]: Async] private (permits: Semaphore[F]) {
  def apply(app: HttpApp[F]): HttpApp[F] = Kleisli { request =>
    if (request.uri.path.renderString == "/health/live") app(request)
    else
      permits.tryPermit.use {
        case true => app(request)
        case false =>
          Async[F].pure(
            Response[F](Status.ServiceUnavailable).withEntity(
              Json.obj(
                "code"       -> Json.fromString("OVERLOADED"),
                "message"    -> Json.fromString("retry with backoff"),
                "request_id" -> Json.fromString(Protocol.id),
                "retryable"  -> Json.True
              )
            )
          )
      }
  }
}
object RequestGate {
  def resource[F[_]: Async](maximum: Int): Resource[F, RequestGate[F]] = {
    require(maximum > 0, "maximum in-flight requests must be positive")
    Resource.eval(Semaphore[F](maximum.toLong).map(new RequestGate(_)))
  }
}
