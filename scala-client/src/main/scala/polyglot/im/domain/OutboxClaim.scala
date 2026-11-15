package polyglot.im.domain

/** 已领取的通知意图、已完成尝试次数与围栏租约；确认必须携带原租约。 */
final case class OutboxClaim(event: NotificationIntent, attempts: Int, lease: String)
