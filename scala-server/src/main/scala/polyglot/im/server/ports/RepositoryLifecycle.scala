package polyglot.im.server.ports

import io.circe.Json

/** 可靠存储的初始化、就绪检查、维护和显式事件清理入口。 */
trait RepositoryLifecycle[F[_]] {

  /** 加载当前账号缓存、建立设备会话并补齐可靠消息状态。 */
  def initialize: F[Unit]

  /** 检查当前表结构已初始化且连接指向可写主库。 */
  def ready: F[Boolean]

  /** 有界清理过期票据和限流窗口，不自动删除可靠消息或幂等记录。 */
  def maintain: F[Unit]

  /** 按截止时间清理可靠事件并更新留存水位，使旧游标走显式快照恢复。 */
  def purgeEvents(beforeEpochSeconds: Long): F[Unit]
}
