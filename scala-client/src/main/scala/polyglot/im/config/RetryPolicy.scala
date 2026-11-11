package polyglot.im.config

import scala.concurrent.duration.*

/** 网络请求的有界指数退避参数；重试沿用原发送意图的幂等键。 */
final case class RetryPolicy(
    maxRetries: Int = 3,
    initialDelay: FiniteDuration = 250.millis,
    retryAfterLimit: FiniteDuration = 30.seconds
) {
  require(maxRetries >= 0 && initialDelay >= Duration.Zero && retryAfterLimit >= Duration.Zero, "invalid retry policy")
}
