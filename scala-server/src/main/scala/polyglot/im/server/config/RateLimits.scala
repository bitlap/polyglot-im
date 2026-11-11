package polyglot.im.server.config

/** 每应用、角色和身份的每分钟请求配额，多个实例共享计数。 */
final case class RateLimits(userPerMinute: Int = 600, servicePerMinute: Int = 120) {
  require(userPerMinute > 0 && servicePerMinute > 0, "rate limits must be positive")
}
