package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

import io.circe.Json

/** 管理设备会话、推送偏好、接收确认和一次性 WebSocket 票据。 */
trait DeviceRepository[F[_]] {

  /** 创建或激活当前用户的设备会话，后续 token 更新必须属于活跃会话。 */
  def register(who: Identity, device: String): F[Unit]

  /** 设置活跃设备的 token、静音和正文预览偏好，避免迟到请求重新激活已退出设备。 */
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit]

  /** 设置 token（None 表示解绑），保留未指定的静音和正文预览偏好。 */
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit]

  /** 读取当前用户指定设备的推送偏好；未注册设备返回默认偏好，不激活会话。 */
  def preferences(who: Identity, device: String): F[Json]

  /** 停用当前设备并解绑推送 token，不影响其他账号或设备会话。 */
  def logout(who: Identity, device: String): F[Unit]

  /** 检查设备是否仍属于当前用户的活跃会话。 */
  def active(who: Identity, device: String): F[Boolean]

  /** 确认已完成本地提交的接收位置，不推进用户阅读水位。 */
  def ack(who: Identity, device: String, value: Long): F[Unit]

  /** 为活跃设备签发短期一次性 WS 票据，绑定用户、应用和身份到期时间。 */
  def ticket(who: Identity, device: String): F[String]

  /** 原子消费一次性票据并返回其绑定身份与设备；过期或复用时拒绝。 */
  def consumeTicket(token: String): F[(Identity, String)]
}
