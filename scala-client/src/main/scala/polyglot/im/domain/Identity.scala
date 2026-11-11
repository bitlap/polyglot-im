package polyglot.im.domain
import java.nio.charset.StandardCharsets.UTF_8
import io.circe.Json

/** IM 请求失败，保存 HTTP 状态、稳定业务错误码及可展示的错误信息。 */
final case class ImError(status: Int, code: String, message: String) extends RuntimeException(message)

/** 环境、应用和用户组成的缓存隔离边界，不用设备身份代替账号。 */
final case class AccountScope(environment: String, appId: String, userId: String) {

  /** 派生当前作用域的稳定隔离键，不把不同应用或用户的缓存混在一起。 */
  def key: String = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
    Json
      .arr(Json.fromString(environment.stripSuffix("/")), Json.fromString(appId), Json.fromString(userId))
      .noSpaces
      .getBytes(UTF_8)
  )
}
