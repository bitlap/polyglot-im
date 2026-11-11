package polyglot.im.server.ports

import polyglot.im.server.domain.PushJob
import polyglot.im.server.domain.PushResult
import polyglot.im.server.domain.PushTarget

import io.circe.Json

/** 管理推送任务租约、投递条件和带领取标识的结果回写。 */
trait PushRepository[F[_]] {

  /** 按默认批量和租约领取可投递任务，不在数据库事务内调用推送网络。 */
  def claim: F[List[PushJob]]

  /** 有界领取到期任务并分配独立租约标识；并发 Worker 不重复拥有同一领取。 */
  def claimBatch(batchSize: Int, leaseSeconds: Int): F[List[PushJob]]

  /** 投递前重新核对消息、设备、静音和已读状态；无需投递时返回空结果。 */
  def pushTarget(job: PushJob): F[Option[PushTarget]]

  /** 按领取标识回写推送结果；过期 Worker 不能覆盖新租约或误清新 token。 */
  def finish(job: PushJob, result: PushResult, token: Option[String]): F[Unit]
}
