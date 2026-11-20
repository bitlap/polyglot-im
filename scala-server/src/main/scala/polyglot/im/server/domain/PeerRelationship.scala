package polyglot.im.server.domain

/** 业务目录返回的实时用户关系；following 表示请求者关注对方，不包含业务用户资料。 */
final case class PeerRelationship(exists: Boolean, following: Boolean, followsYou: Boolean) {

  /** 仅双方均关注才视为互关；不存在的账号始终不可见。 */
  def mutual: Boolean = exists && following && followsYou
}
