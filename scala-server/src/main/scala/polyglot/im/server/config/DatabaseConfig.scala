package polyglot.im.server.config

/** PostgreSQL 连接与池参数；不在日志或错误中回显 URL、用户名、密码。 */
final case class DatabaseConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int = 8,
    connectionTimeoutMs: Long = 10000L,
    statementTimeoutMs: Long = 15000L,
    lockTimeoutMs: Long = 3000L
) {

  /** 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。 */
  override def toString: String = "DatabaseConfig(<redacted>)"
  require(url.startsWith("jdbc:postgresql:"), "PostgreSQL JDBC URL required")
  require(user.trim.nonEmpty, "database user required")
  require(maxPoolSize > 0, "database pool size must be positive")
  require(connectionTimeoutMs >= 250L, "database connection timeout must be >= 250 ms")
  require(
    statementTimeoutMs > 0 && lockTimeoutMs > 0 && lockTimeoutMs <= statementTimeoutMs,
    "invalid database timeouts"
  )
}
