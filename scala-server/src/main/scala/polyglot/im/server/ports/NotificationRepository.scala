package polyglot.im.server.ports
import polyglot.im.server.domain.{Identity, Notification, StoredMessage}
trait NotificationRepository[F[_]] { def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] }
