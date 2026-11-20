package polyglot.im.server.config

import scala.concurrent.duration.FiniteDuration

/** HOCON 数据库与超时字段；输出时隐藏连接信息。 */
final case class DatabaseInputSettings(
    url: String,
    user: String,
    password: String,
    poolSize: Int,
    connectionTimeout: FiniteDuration,
    statementTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
) {

  /** 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。 */
  override def toString: String = "DatabaseInputSettings(<redacted>)"
}
