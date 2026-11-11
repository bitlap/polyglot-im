package polyglot.im.server.config

import scala.concurrent.duration.*

final case class PushWorkerSettings(
    parallelism: Int,
    leaseDuration: FiniteDuration,
    jobTimeout: FiniteDuration,
    pollInterval: FiniteDuration
) {
  require(parallelism > 0 && parallelism <= 256, "invalid push parallelism")
  require(
    jobTimeout > scala.concurrent.duration.Duration.Zero && pollInterval > scala.concurrent.duration.Duration.Zero,
    "invalid push timeouts"
  )
  require(
    leaseDuration >= jobTimeout + 5.seconds && leaseDuration.toSeconds <= Int.MaxValue,
    "lease must exceed job timeout by at least five seconds"
  )
}
