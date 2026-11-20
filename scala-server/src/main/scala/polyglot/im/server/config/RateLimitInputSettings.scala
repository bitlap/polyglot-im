package polyglot.im.server.config

/** HOCON 中用户与服务身份的每分钟请求配额。 */
final case class RateLimitInputSettings(userPerMinute: Int, servicePerMinute: Int)
