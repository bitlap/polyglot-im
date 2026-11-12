package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

/** 验证宿主用户登录态的扩展点；只能返回当前应用的用户身份，不能授予生产者权限。 */
trait UserAuthenticator[F[_]] {

  /** 校验一次当前凭证；撤销、过期或依赖不可用时拒绝，不缓存失效登录态。 */
  def authenticateUser(token: String): F[Identity]
}
