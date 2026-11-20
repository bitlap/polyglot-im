package polyglot.im.server.service
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import polyglot.im.server.domain.*
import polyglot.im.server.ports.{ClientRepository, PeerDirectory}

import polyglot.im.server.domain.ApiError.{Code, Message}

/** 应用用例不引用 HTTP/JDBC；每个仓储方法保持原子事务边界。 */
final class MessagingService[F[_]: Async](repository: ClientRepository[F], directory: Option[PeerDirectory[F]] = None) {
  private val notifications = new NotificationService[F](repository)
  private def user(who: Identity): F[Unit] =
    if (who.role == "user") Async[F].unit
    else Async[F].raiseError(ApiError(403, Code.Forbidden, Message.UserIdentityRequired))

  /** 校验服务身份后幂等提交通知；返回消息及是否首次创建，同键不同内容拒绝写入。 */
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] = notifications.send(sender, command)

  /** 按 before_seq 向前分页读取通知历史；历史页不推进增量游标。 */
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean, Position)] =
    user(who) *> repository.history(who, before, limit)

  /** 按消息身份读取当前用户可见的通知或单聊消息，不向越权请求泄露存在性。 */
  def get(who: Identity, id: String): F[StoredMessage] = user(who) *> repository.get(who, id)

  /** 取得当前用户的消息、事件、阅读和留存水位。 */
  def position(who: Identity): F[Position] = user(who) *> repository.position(who)

  /** 在一致性读取中取得通知快照及其对应水位。 */
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = user(who) *> repository.snapshot(who)

  /** 在同一快照中读取通知、会话摘要和水位，另返回会话是否还有后续页。 */
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)] =
    user(who) *> repository.snapshotPage(who)

  /** 校验用户身份后读取指定游标之后的持久事件，不处理客户端缓存或快照重建。 */
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.sync(who, from, limit)

  /** 为活跃设备读取指定位置之后的事件，拒绝已退出的设备继续同步。 */
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.syncDevice(who, device, from, limit)

  /** 原子标记所选通知已读或删除；不推进累计阅读水位。 */
  def updateNotifications(who: Identity, ids: List[String], delete: Boolean): F[Position] =
    user(who) *> repository.updateNotifications(who, ids, delete)

  /** 单调推进通知阅读水位并返回当前统计，不超过已提交的消息高水位。 */
  def read(who: Identity, seq: Long): F[Position] = user(who) *> repository.read(who, seq)

  /** 按对方用户身份创建或取得唯一单聊会话。 */
  def createDirect(who: Identity, peer: String, credential: String = ""): F[Json] =
    user(who) *> relationship(who, peer, credential).flatMap(repository.createDirect(who, peer, _))

  /** 从 after 位置分页取得当前用户可见的单聊会话摘要。 */
  def conversations(who: Identity, after: String, limit: Int): F[Json] =
    user(who) *> repository.conversations(who, after, limit)

  /** 按 before 序号读取成员可见的单聊历史，并返回会话状态。 */
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json] =
    user(who) *> repository.directHistory(who, id, before, limit)

  /** 在同一事务校验成员并幂等写入单聊消息、双方事件、计数和推送任务。 */
  def sendDirect(who: Identity, id: String, data: Json, credential: String = ""): F[(StoredMessage, Boolean)] =
    user(who) *> repository
      .directPeer(who, id)
      .flatMap(peer => relationship(who, peer, credential).flatMap(repository.sendDirect(who, id, data, _)))

  /** 分页查询自己的偏好及黑名单，不需要读取对方业务资料。 */
  def listPeerPreferences(who: Identity, after: String, limit: Int, blockedOnly: Boolean): F[Json] =
    user(who) *> repository.listPeerPreferences(who, after, limit, blockedOnly)

  private def peerRelationships(
      who: Identity,
      ids: List[String],
      credential: String
  ): F[Map[String, PeerRelationship]] = {
    val unavailable = ApiError(503, Code.DirectUnavailable, Message.DirectUnavailable)
    directory
      .fold(Async[F].raiseError[Map[String, PeerRelationship]](unavailable))(_.relationships(who, ids, credential))
  }
  private def relationship(who: Identity, peer: String, credential: String): F[Option[PeerRelationship]] =
    directory.traverse(
      _.relationships(who, List(peer), credential).map(_.getOrElse(peer, PeerRelationship(false, false, false)))
    )

  /** 读取当前用户的私信权限和在线隐私；不读取设备全局推送开关。 */
  def directSettings(who: Identity): F[Json] = user(who) *> repository.directSettings(who)

  /** 持久化已校验的私信与在线隐私白名单值。 */
  def updateDirectSettings(who: Identity, data: Json): F[Json] = user(who) *> repository.updateDirectSettings(who, data)

  /** 查询自己的会话偏好并逐次核对实时业务关系，拒绝原因不暴露对方配置。 */
  def peerPreferences(who: Identity, peer: String, credential: String): F[Json] =
    user(who) *> peerRelationships(who, List(peer), credential).flatMap(r =>
      repository.peerPreferences(who, peer, r.getOrElse(peer, PeerRelationship(false, false, false)))
    )

  /** 持久化当前用户对指定人的会话偏好，不接受代其他账号写入。 */
  def updatePeerPreferences(who: Identity, peer: String, data: Json): F[Json] =
    user(who) *> repository.updatePeerPreferences(who, peer, data)

  /** 以捕获的会话水位隐藏或清空本人记录，避免误删后来的新消息。 */
  def updateDirectVisibility(who: Identity, id: String, through: Long, hide: Boolean, clear: Boolean): F[Json] =
    user(who) *> repository.updateDirectVisibility(who, id, through, hide, clear)

  /** 执行服务端本人删除／限时撤回，权限和时间窗口由持久化事务复核。 */
  def updateDirectMessage(who: Identity, id: String, message: String, recall: Boolean): F[Json] =
    user(who) *> repository.updateDirectMessage(who, id, message, recall)

  /** 活跃设备续约或主动关闭租约；租约不写入可靠消息事件流。 */
  def updatePresence(who: Identity, device: String, online: Boolean): F[Unit] =
    user(who) *> repository.updatePresence(who, device, online)

  /** 对最多 50 个用户批量查询，关系核对失败时不推断在线或离线。 */
  def presence(who: Identity, peers: List[String], credential: String): F[Json] = user(who) *> Async[F].delay {
    if (peers.isEmpty || peers.distinct.size != peers.size || peers.size > 50)
      throw ApiError(422, Code.InvalidArgument, Message.InvalidDirectPreferences)
    peers.foreach(id => Protocol.bounded(id, "user_id"))
  } *> peerRelationships(who, peers, credential).flatMap(repository.presence(who, _))

  /** 推进当前成员的单聊阅读位置，并向双方同步已读状态。 */
  def readDirect(who: Identity, id: String, seq: Long): F[Json] = user(who) *> repository.readDirect(who, id, seq)

  /** 创建或激活当前用户的设备会话，后续 token 更新必须属于活跃会话。 */
  def register(who: Identity, device: String): F[Unit] = user(who) *> repository.register(who, device)

  /** 设置活跃设备的 token、静音和正文预览偏好，避免迟到请求重新激活已退出设备。 */
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit] =
    user(who) *> repository.device(who, device, token, muted, preview)

  /** 设置 token（None 表示解绑），保留未指定的静音和正文预览偏好。 */
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit] = user(who) *> repository.patchDevice(who, device, token, muted, preview)

  /** 读取当前用户指定设备的推送偏好；未注册设备返回默认偏好，不激活会话。 */
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
