package polyglot.im.server.service
import cats.effect.Async
import polyglot.im.server.domain.{Identity, Notification, StoredMessage, ApiError}
import polyglot.im.server.ports.NotificationRepository

/** 通知范围是业务策略，不依赖 HTTP 认证头或某个数据库驱动。 */
final class NotificationService[F[_]: Async](repository: NotificationRepository[F]) {
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] =
    if (
      sender.role != "service" || !(sender.types("*") || sender.types(command.kind)) ||
      !(sender.recipients("*") || sender.recipients(command.recipient))
    )
      Async[F].raiseError(ApiError(403, "FORBIDDEN", "recipient or type is outside service scope"))
    else repository.send(sender, command)
}
