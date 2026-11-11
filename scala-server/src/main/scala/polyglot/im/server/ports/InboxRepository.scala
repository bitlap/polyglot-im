package polyglot.im.server.ports

import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Notification
import polyglot.im.server.domain.Position
import polyglot.im.server.domain.StoredMessage

import io.circe.Json

trait InboxRepository[F[_]] extends NotificationRepository[F] {
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)]
  def get(who: Identity, id: String): F[StoredMessage]
  def position(who: Identity): F[Position]
  def snapshot(who: Identity): F[(Position, List[StoredMessage])]
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)]
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)]
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)]
  def read(who: Identity, seq: Long): F[Position]
}
