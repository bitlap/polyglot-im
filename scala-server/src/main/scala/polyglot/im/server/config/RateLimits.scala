package polyglot.im.server.config

final case class RateLimits(userPerMinute: Int = 600, servicePerMinute: Int = 120) {
  require(userPerMinute > 0 && servicePerMinute > 0, "rate limits must be positive")
}
