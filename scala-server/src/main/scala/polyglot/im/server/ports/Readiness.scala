package polyglot.im.server.ports

/** 宿主注入的持久化就绪检查，不能以进程存活替代存储就绪。 */
trait Readiness[F[_]] {

  /** 检查服务依赖当前是否就绪；结果不等同于进程存活。 */
  def check: F[Boolean]
}
