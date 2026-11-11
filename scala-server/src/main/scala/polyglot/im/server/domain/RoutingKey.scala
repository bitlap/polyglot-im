package polyglot.im.server.domain

object RoutingKey { def apply(who: Identity): String = Protocol.conversation(who.app, who.user) }
