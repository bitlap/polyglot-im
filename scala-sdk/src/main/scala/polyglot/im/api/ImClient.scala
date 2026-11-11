package polyglot.im.api

import cats.effect.{Async, Ref}
import cats.syntax.all.*
import cats.effect.std.Semaphore
import io.circe.Json
import polyglot.im.domain.{AccountScope, LocalCache}
import polyglot.im.ports.{RemoteApi, LocalStore, SocketConnector, WireCodec}

object ImClient {
  def create[F[_]: Async](
      api: RemoteApi[F],
      scope: AccountScope,
      deviceId: String,
      store: LocalStore[F],
      socket: SocketConnector[F],
      codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
  ): F[polyglot.im.service.ImClient[F]] = for {
    cache <- Ref.of[F, Json](LocalCache.empty); gate <- Semaphore[F](1); closed <- Ref.of[F, Boolean](false)
  } yield new polyglot.im.service.ImClient(
    api.scoped(scope),
    scope,
    deviceId,
    store,
    socket,
    cache,
    gate,
    closed,
    codec
  )
}
