package polyglot.im.server

/** PostgreSQL 连接与池参数；不在日志或错误中回显 URL、用户名、密码。 */
final case class DatabaseConfig(
    url: String,
    user: String,
    password: String,
    maxPoolSize: Int = 8,
    connectionTimeoutMs: Long = 10000L
) {
  require(url.startsWith("jdbc:postgresql:"), "PostgreSQL JDBC URL required")
  require(user.trim.nonEmpty, "database user required")
  require(maxPoolSize > 0, "database pool size must be positive")
  require(connectionTimeoutMs >= 250L, "database connection timeout must be >= 250 ms")
}

object DatabaseConfig {
  def fromEnvironment(env: Map[String, String]): Either[String, DatabaseConfig] = for {
    url <- env
      .get("POLYGLOT_IM_DATABASE_URL")
      .filter(_.startsWith("jdbc:postgresql:"))
      .toRight("POLYGLOT_IM_DATABASE_URL must be a PostgreSQL JDBC URL")
    user <- env
      .get("POLYGLOT_IM_DATABASE_USER")
      .filter(_.trim.nonEmpty)
      .toRight("missing POLYGLOT_IM_DATABASE_USER")
    pool <- env
      .getOrElse("POLYGLOT_IM_DATABASE_POOL_SIZE", "8")
      .toIntOption
      .filter(_ > 0)
      .toRight("POLYGLOT_IM_DATABASE_POOL_SIZE must be positive")
    timeout <- env
      .getOrElse("POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS", "10000")
      .toLongOption
      .filter(_ >= 250)
      .toRight("POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS must be >= 250")
  } yield DatabaseConfig(url, user, env.getOrElse("POLYGLOT_IM_DATABASE_PASSWORD", ""), pool, timeout)
}
