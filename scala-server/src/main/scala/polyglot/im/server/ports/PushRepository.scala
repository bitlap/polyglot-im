package polyglot.im.server.ports

import polyglot.im.server.domain.PushJob
import polyglot.im.server.domain.PushResult
import polyglot.im.server.domain.PushTarget

import io.circe.Json

trait PushRepository[F[_]] {
  def claim: F[List[PushJob]]
  def claimBatch(batchSize: Int, leaseSeconds: Int): F[List[PushJob]]
  def pushTarget(job: PushJob): F[Option[PushTarget]]
  def finish(job: PushJob, result: PushResult, token: Option[String]): F[Unit]
}
