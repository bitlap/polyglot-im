package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

trait ChangeNotifications[F[_]] {
  def connected: F[Boolean]

  /** Wake-up hints, never the source of truth. Emit immediately on subscription and reconcile at least every five
    * seconds even when the notification backend is unavailable. Consumers reload durable events after every hint;
    * duplicate hints are safe.
    */
  def subscribe(who: Identity): cats.effect.Resource[F, fs2.Stream[F, Unit]]
}
