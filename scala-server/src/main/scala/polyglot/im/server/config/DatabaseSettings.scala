package polyglot.im.server.config

import polyglot.im.server.domain.ApiError.Message

/** PostgreSQL 连接与池参数；不在日志或错误中回显 URL、用户名、密码。 */
final case class DatabaseSettings(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int = 8,
    connectionTimeoutMs: Long = 10000L,
    statementTimeoutMs: Long = 15000L,
    lockTimeoutMs: Long = 3000L
) {

  /** 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。 */
  override def toString: String = "DatabaseSettings(<redacted>)"
  require(url.startsWith("jdbc:postgresql:"), Message.PostgresUrlRequired)
  require(user.trim.nonEmpty, Message.DatabaseUserRequired)
  require(maxPoolSize > 0, Message.InvalidPoolSize)
  require(connectionTimeoutMs >= 250L, Message.InvalidConnectionTimeout)
  require(
    statementTimeoutMs > 0 && lockTimeoutMs > 0 && lockTimeoutMs <= statementTimeoutMs,
    Message.InvalidDatabaseTimeouts
  )
}
