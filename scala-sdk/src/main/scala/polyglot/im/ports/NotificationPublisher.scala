package polyglot.im.ports
import io.circe.Json
import polyglot.im.domain.Message
trait NotificationPublisher[F[_]] {
  def send(recipientId: String, clientMessageId: String, kind: String, payload: Json): F[Message]
}
