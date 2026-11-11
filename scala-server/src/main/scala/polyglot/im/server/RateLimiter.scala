package polyglot.im.server

import cats.effect.{Async, Ref}
import cats.syntax.all.*

trait RateLimiter[F[_]] {
  def allow(who: Identity, maximum: Int): F[Boolean]
}
object RateLimiter {

  /** 生产默认：原子数据库计数，实例切换不重置配额，数据库故障时不放行。 */
  def postgres[F[_]](store: Store[F]): RateLimiter[F] = new RateLimiter[F] {
    def allow(who: Identity, maximum: Int): F[Boolean] = store.allow(who, maximum)
  }

  /** 仅用于隔离的单元测试；不用于多实例部署。 */
  def inMemory[F[_]: Async](
      counts: Ref[F, Map[String, (Long, Int)]],
      now: () => Long = () => Protocol.now
  ): RateLimiter[F] =
    new RateLimiter[F] {
      def allow(who: Identity, maximum: Int): F[Boolean] = counts.modify { old =>
        val minute  = now() / 60
        val key     = ChangeFeed.key(who) + ":" + who.role
        val cleaned = old.filter(_._2._1 >= minute)
        val count   = cleaned.get(key).filter(_._1 == minute).fold(0)(_._2)
        if (count >= maximum || (cleaned.size >= 10000 && !cleaned.contains(key))) (cleaned, false)
        else (cleaned.updated(key, (minute, count + 1)), true)
      }
    }
}
