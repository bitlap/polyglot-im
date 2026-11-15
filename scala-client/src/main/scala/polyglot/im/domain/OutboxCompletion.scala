package polyglot.im.domain

/** 一次发送的持久化结果；retryDelaySeconds 由派发策略计算，存储以数据库时钟安排下次领取。 */
final case class OutboxCompletion(state: OutboxState, retryDelaySeconds: Long, error: Option[String]) {
  require(retryDelaySeconds >= 0, "retry delay must be non-negative")
}
