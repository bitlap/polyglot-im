package polyglot.im.server.ports

/** 宿主注入的持久化就绪检查，不能以进程存活替代存储就绪。 */
trait Readiness[F[_]] {
  def check: F[Boolean]
}
