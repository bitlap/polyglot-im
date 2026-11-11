package polyglot.im.server.ports

import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.StoredMessage

import io.circe.Json

trait ConversationRepository[F[_]] {
  def createDirect(who: Identity, peer: String): F[Json]
  def conversations(who: Identity, after: String, limit: Int): F[Json]
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json]
  def sendDirect(who: Identity, id: String, data: Json): F[(StoredMessage, Boolean)]
  def readDirect(who: Identity, id: String, seq: Long): F[Json]
}
