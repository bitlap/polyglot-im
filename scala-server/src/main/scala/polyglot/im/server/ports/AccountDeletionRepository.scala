package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

/** 可信业务服务发起不可恢复注销；保留最小墓碑，禁止旧设备或迟到任务重建用户数据。 */
trait AccountDeletionRepository[F[_]] {

  /** 仅清理 who.app 内目标账号；原子擦除正文、设备、票据和推送，强制受影响用户重建快照。 */
  def deleteAccount(who: Identity, user: String): F[Unit]
}
