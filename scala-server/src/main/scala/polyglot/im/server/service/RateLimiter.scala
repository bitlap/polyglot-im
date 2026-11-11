package polyglot.im.server.service

import polyglot.im.server.domain.RoutingKey
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol
import polyglot.im.server.ports.ClusterRepository

import cats.effect.{Async, Ref}
import cats.syntax.all.*

import polyglot.im.server.ports.RateLimiter as RateLimiterPort

/** 按身份消耗请求配额的端口及装配入口；生产计数存储在 PostgreSQL。 */
object RateLimiter {

  /** 生产默认：原子数据库计数，实例切换不重置配额，数据库故障时不放行。 */
  def postgres[F[_]](store: ClusterRepository[F]): RateLimiterPort[F] = new RateLimiterPort[F] {

    /** 在共享身份窗口内消耗一次配额，返回是否允许继续执行。 */
    def allow(who: Identity, maximum: Int): F[Boolean] = store.allow(who, maximum)
  }

  /** 仅用于隔离的单元测试；不用于多实例部署。 */
  def inMemory[F[_]: Async](
      counts: Ref[F, Map[String, (Long, Int)]],
      now: () => Long = () => Protocol.now
  ): RateLimiterPort[F] =
    new RateLimiterPort[F] {

      /** 在共享身份窗口内消耗一次配额，返回是否允许继续执行。 */
      def allow(who: Identity, maximum: Int): F[Boolean] = counts.modify { old =>
        val minute  = now() / 60
        val key     = RoutingKey(who) + ":" + who.role
        val cleaned = old.filter(_._2._1 >= minute)
        val count   = cleaned.get(key).filter(_._1 == minute).fold(0)(_._2)
        if (count >= maximum || (cleaned.size >= 10000 && !cleaned.contains(key))) (cleaned, false)
        else (cleaned.updated(key, (minute, count + 1)), true)
      }
    }
}
