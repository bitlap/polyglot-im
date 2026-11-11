package polyglot.im.server.ports

import polyglot.im.server.domain.PushResult
import polyglot.im.server.domain.PushTarget

/** 向外部推送通道提交提醒；提供方接受不等于设备收到或用户已读。 */
trait PushProvider[F[_]] {

  /** 向当前推送目标提交提醒并返回结果分类；通道接受不等于用户送达或已读。 */
  def send(target: PushTarget): F[PushResult]
}
