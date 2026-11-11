package polyglot.im.domain
import io.circe.Json

final case class NotificationIntent(eventId: String, recipient: String, kind: String, payload: Json)
