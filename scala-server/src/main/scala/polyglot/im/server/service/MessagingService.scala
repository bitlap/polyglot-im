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
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] = notifications.send(sender, command)
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)] =
    user(who) *> repository.history(who, before, limit)
  def get(who: Identity, id: String): F[StoredMessage]            = user(who) *> repository.get(who, id)
  def position(who: Identity): F[Position]                        = user(who) *> repository.position(who)
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = user(who) *> repository.snapshot(who)
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)] =
    user(who) *> repository.snapshotPage(who)
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.sync(who, from, limit)
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    user(who) *> repository.syncDevice(who, device, from, limit)
  def read(who: Identity, seq: Long): F[Position]        = user(who) *> repository.read(who, seq)
  def createDirect(who: Identity, peer: String): F[Json] = user(who) *> repository.createDirect(who, peer)
  def conversations(who: Identity, after: String, limit: Int): F[Json] =
    user(who) *> repository.conversations(who, after, limit)
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json] =
    user(who) *> repository.directHistory(who, id, before, limit)
  def sendDirect(who: Identity, id: String, data: Json): F[(StoredMessage, Boolean)] =
    user(who) *> repository.sendDirect(who, id, data)
  def readDirect(who: Identity, id: String, seq: Long): F[Json] = user(who) *> repository.readDirect(who, id, seq)
  def register(who: Identity, device: String): F[Unit]          = user(who) *> repository.register(who, device)
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit] =
    user(who) *> repository.device(who, device, token, muted, preview)
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit] = user(who) *> repository.patchDevice(who, device, token, muted, preview)
  def preferences(who: Identity, device: String): F[Json]      = user(who) *> repository.preferences(who, device)
  def logout(who: Identity, device: String): F[Unit]           = user(who) *> repository.logout(who, device)
  def active(who: Identity, device: String): F[Boolean]        = user(who) *> repository.active(who, device)
  def ack(who: Identity, device: String, value: Long): F[Unit] = user(who) *> repository.ack(who, device, value)
  def ticket(who: Identity, device: String): F[String]         = user(who) *> repository.ticket(who, device)
  def consumeTicket(token: String): F[(Identity, String)]      = repository.consumeTicket(token)
}
