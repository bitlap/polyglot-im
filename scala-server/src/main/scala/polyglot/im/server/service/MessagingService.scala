package polyglot.im.server.service
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import polyglot.im.server.domain.*
import polyglot.im.server.ports.ClientRepository

/** 应用用例不引用 HTTP/JDBC；每个仓储方法保持原子事务边界。 */
final class MessagingService[F[_]: Async](repository: ClientRepository[F]) {
  private val notifications = new NotificationService[F](repository)
  private def user(who: Identity): F[Unit] =
    if (who.role == "user") Async[F].unit
    else Async[F].raiseError(ApiError(403, "FORBIDDEN", "user identity required"))

  /** 校验服务身份后幂等提交通知；返回消息及是否首次创建，同键不同内容拒绝写入。 */
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] = notifications.send(sender, command)

  /** 按 before_seq 向前分页读取通知历史；历史页不推进增量游标。 */
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)] =
    user(who) *> repository.history(who, before, limit)

  /** 按消息身份读取当前用户可见的通知，不向越权请求泄露存在性。 */
  def get(who: Identity, id: String): F[StoredMessage] = user(who) *> repository.get(who, id)

  /** 取得当前用户的消息、事件、阅读和留存水位。 */
  def position(who: Identity): F[Position] = user(who) *> repository.position(who)

  /** 在一致性读取中取得通知快照及其对应水位。 */
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = user(who) *> repository.snapshot(who)

  /** 在同一快照中读取通知、会话摘要和水位，另返回会话是否还有后续页。 */
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)] =
    user(who) *> repository.snapshotPage(who)

  /** 串行追赶增量；游标过期时取快照，缓存与游标提交成功后才发布新状态。 */
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.sync(who, from, limit)

  /** 为活跃设备读取指定位置之后的事件，拒绝已退出的设备继续同步。 */
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.syncDevice(who, device, from, limit)

  /** 单调推进通知阅读水位并返回当前统计，不超过已提交的消息高水位。 */
  def read(who: Identity, seq: Long): F[Position] = user(who) *> repository.read(who, seq)

  /** 按对方用户身份创建或取得唯一单聊会话。 */
  def createDirect(who: Identity, peer: String): F[Json] = user(who) *> repository.createDirect(who, peer)

  /** 从 after 位置分页取得当前用户可见的单聊会话摘要。 */
  def conversations(who: Identity, after: String, limit: Int): F[Json] =
    user(who) *> repository.conversations(who, after, limit)

  /** 按 before 序号读取成员可见的单聊历史，并返回会话状态。 */
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json] =
    user(who) *> repository.directHistory(who, id, before, limit)

  /** 在同一事务校验成员并幂等写入单聊消息、双方事件、计数和推送任务。 */
  def sendDirect(who: Identity, id: String, data: Json): F[(StoredMessage, Boolean)] =
    user(who) *> repository.sendDirect(who, id, data)

  /** 推进当前成员的单聊阅读位置，并向双方同步已读状态。 */
  def readDirect(who: Identity, id: String, seq: Long): F[Json] = user(who) *> repository.readDirect(who, id, seq)

  /** 创建或激活当前用户的设备会话，后续 token 更新必须属于活跃会话。 */
  def register(who: Identity, device: String): F[Unit] = user(who) *> repository.register(who, device)

  /** 设置活跃设备的 token、静音和正文预览偏好，避免迟到请求重新激活已退出设备。 */
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit] =
    user(who) *> repository.device(who, device, token, muted, preview)

  /** 只更新请求明确提供的设备字段，保留未指定的 token 或提醒偏好。 */
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit] = user(who) *> repository.patchDevice(who, device, token, muted, preview)

  /** 读取当前用户活跃设备的推送设置。 */
  def preferences(who: Identity, device: String): F[Json] = user(who) *> repository.preferences(who, device)

  /** 停用当前设备并解绑推送 token，不影响其他账号或设备会话。 */
  def logout(who: Identity, device: String): F[Unit] = user(who) *> repository.logout(who, device)

  /** 检查设备是否仍属于当前用户的活跃会话。 */
  def active(who: Identity, device: String): F[Boolean] = user(who) *> repository.active(who, device)

  /** 确认已完成本地提交的接收位置，不推进用户阅读水位。 */
  def ack(who: Identity, device: String, value: Long): F[Unit] = user(who) *> repository.ack(who, device, value)

  /** 为活跃设备签发短期一次性 WS 票据，绑定用户、应用和身份到期时间。 */
  def ticket(who: Identity, device: String): F[String] = user(who) *> repository.ticket(who, device)

  /** 原子消费一次性票据并返回其绑定身份与设备；过期或复用时拒绝。 */
  def consumeTicket(token: String): F[(Identity, String)] = repository.consumeTicket(token)
}
