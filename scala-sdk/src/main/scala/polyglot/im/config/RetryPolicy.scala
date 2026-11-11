package polyglot.im.config

import scala.concurrent.duration.*
final case class RetryPolicy(
    maxRetries: Int = 3,
    initialDelay: FiniteDuration = 250.millis,
    retryAfterLimit: FiniteDuration = 30.seconds
) {
  require(maxRetries >= 0 && initialDelay >= Duration.Zero && retryAfterLimit >= Duration.Zero, "invalid retry policy")
}
