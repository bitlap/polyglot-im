package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

/** 验证登录或服务凭证并返回可信身份，不能相信客户端自报用户 ID。 */
trait Authenticator[F[_]] {

  /** 验证凭证签名、有效期及作用域，返回可信身份；失败不接受客户端自报身份。 */
  def authenticate(token: String): F[Identity]
}
