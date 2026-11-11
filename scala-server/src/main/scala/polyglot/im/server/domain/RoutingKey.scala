package polyglot.im.server.domain

/** 从应用和接收用户派生跨实例通知路由键，不暴露业务正文。 */
object RoutingKey {

  /** 由应用和用户生成变化通知的隔离路由键。 */
  def apply(who: Identity): String = Protocol.conversation(who.app, who.user)
}
