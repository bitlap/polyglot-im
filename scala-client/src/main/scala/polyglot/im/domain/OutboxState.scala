package polyglot.im.domain

/** 业务 Outbox 的现有持久化状态值，不改变数据库结构。 */
enum OutboxState(val value: String) {
  case Pending extends OutboxState("pending")
  case Sent    extends OutboxState("sent")
  case Dead    extends OutboxState("dead")
}
