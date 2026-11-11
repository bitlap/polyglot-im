package polyglot.im.domain
import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json

final case class ImError(status: Int, code: String, message: String) extends RuntimeException(message)
final case class AccountScope(environment: String, appId: String, userId: String) {
  def key: String = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
    Json
      .arr(Json.fromString(environment.stripSuffix("/")), Json.fromString(appId), Json.fromString(userId))
      .noSpaces
      .getBytes(UTF_8)
  )
}
