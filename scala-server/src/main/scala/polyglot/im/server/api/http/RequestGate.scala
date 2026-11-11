package polyglot.im.server.api.http

import polyglot.im.server.domain.ApiError

import cats.data.Kleisli
import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import org.http4s.{HttpApp, Response, Status}
import org.http4s.circe.*

/** 限制正在处理的 HTTP 请求；满载立即拒绝，避免等待数据库连接的无界排队。 */
final class RequestGate[F[_]: Async] private (permits: Semaphore[F]) {

  /** 为 HTTP 应用增加在途配额；满载立即返回 503，完成或取消后释放许可。 */
  def apply(app: HttpApp[F]): HttpApp[F] = Kleisli { request =>
    if (request.uri.path.renderString == "/health/live") app(request)
    else
      permits.tryPermit.use {
        case true => app(request)
        case false =>
          Async[F].pure(
            Response[F](Status.ServiceUnavailable).withEntity(
              ApiResponse.failure(ApiError(503, "OVERLOADED", "retry with backoff"))
            )
          )
      }
  }
}

/** 构造正数并发上限的 HTTP 请求门限，健康探针不占业务配额。 */
object RequestGate {

  /** 创建指定最大并发数的请求门限；非正数上限在启动时拒绝。 */
  def resource[F[_]: Async](maximum: Int): Resource[F, RequestGate[F]] = {
    require(maximum > 0, "maximum in-flight requests must be positive")
    Resource.eval(Semaphore[F](maximum.toLong).map(new RequestGate(_)))
  }
}
