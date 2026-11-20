package polyglot.im.server.config

import polyglot.im.server.domain.ApiError.Message

/** 每应用、角色和身份的每分钟请求配额，多个实例共享计数。 */
final case class RateLimitSettings(userPerMinute: Int = 600, servicePerMinute: Int = 120) {
  require(userPerMinute > 0 && servicePerMinute > 0, Message.InvalidRateLimits)
}
