package polyglot.im.service

import cats.effect.Async
import cats.syntax.all.*
import polyglot.im.domain.{ImError, OutboxClaim, OutboxCompletion, OutboxState}
import polyglot.im.ports.{NotificationPublisher, Outbox}

/** 业务通知派发服务；仅依赖存储及发布端口，租约时长由宿主配置，调度生命周期由宿主管理。 */
final class OutboxDispatcher[F[_]: Async](
    outbox: Outbox[F],
    sender: NotificationPublisher[F],
    leaseSeconds: Long = 120
) {
  require(leaseSeconds > 0, "leaseSeconds must be positive")

  private def complete(row: OutboxClaim, state: OutboxState, error: Option[String]): F[Unit] = {
    val delay = math.min(300L, 1L << math.min(row.attempts, 9))
    outbox.complete(row, OutboxCompletion(state, delay, error)).void
  }

  /** 领取一条后在事务外发送；无记录返回 false，取消或未知异常不确认，租约到期后可重放。 */
  def drainOnce: F[Boolean] = outbox.claim(leaseSeconds).flatMap {
    case None => Async[F].pure(false)
    case Some(row) =>
      sender
        .send(row.event.recipient, row.event.eventId, row.event.kind, row.event.payload)
        .attempt
        .flatMap {
          case Right(_) => complete(row, OutboxState.Sent, None)
          case Left(e: ImError) if Set(400, 403, 409, 413, 422)(e.status) =>
            complete(row, OutboxState.Dead, Some(e.code))
          case Left(_: java.io.IOException) => complete(row, OutboxState.Pending, Some("NETWORK_ERROR"))
          case Left(e: ImError)             => complete(row, OutboxState.Pending, Some(e.code))
          case Left(e)                      => Async[F].raiseError(e)
        }
        .as(true)
  }
}
