package polyglot.im.server.api.http

import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Protocol

import io.circe.Json

/** HTTP 响应信封；WebSocket 帧和内部领域 JSON 不使用此包装。 */
object ApiResponse {

  /** 构建 code=200 的成功信封，业务结果放入 data。 */
  def success(data: Json): Json =
    Json.obj("code" -> Json.fromInt(200), "message" -> Json.fromString("success"), "data" -> data)

  /** 构建失败信封，保留稳定 error_code、请求身份和可重试分类。 */
  def failure(error: ApiError): Json = Json.obj(
    "code"       -> Json.fromInt(error.status),
    "message"    -> Json.fromString(error.message),
    "data"       -> Json.Null,
    "error_code" -> Json.fromString(error.code),
    "request_id" -> Json.fromString(Protocol.id),
    "retryable"  -> Json.fromBoolean(error.status >= 500 || error.status == 429)
  )
}
