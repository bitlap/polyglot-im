package polyglot.im.server.ports

import polyglot.im.server.domain.{Identity, PeerRelationship}

/** 宿主业务目录端口；固定当前应用和认证用户，只返回 1–50 个指定用户的存在性及关注关系。 */
trait PeerDirectory[F[_]] {

  /** 每次核对真实业务身份及关系，不缓存授权；credential 仅用于本次可信目录调用，禁止持久化和记录。 */
  def relationships(who: Identity, peers: List[String], credential: String): F[Map[String, PeerRelationship]]
}
