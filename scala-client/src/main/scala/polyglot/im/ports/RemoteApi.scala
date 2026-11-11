package polyglot.im.ports

/** IM 业务请求端口；路径相对统一 API 前缀，身份绑定不能作为认证凭据。 */
trait RemoteApi[F[_]] {

  /** 创建绑定指定账号作用域的请求入口，保留注入的传输和编解码器。 */
  def scoped(scope: polyglot.im.domain.AccountScope): RemoteApi[F]

  /** 执行相对 IM API 的业务请求并返回解包对象；业务或传输失败向调用方传递。 */
  def request(method: String, path: String, body: Option[io.circe.Json] = None): F[io.circe.Json]
}
