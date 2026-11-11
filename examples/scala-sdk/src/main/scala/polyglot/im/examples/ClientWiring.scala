package polyglot.im.examples

import cats.effect.Async
import polyglot.im.api.{ApiClient, ImClient as ImClientFactory}
import polyglot.im.domain.AccountScope
import polyglot.im.ports.{HttpTransport, TokenProvider, LocalStore, SocketConnector, WireCodec}
import polyglot.im.service.{AuthenticatedTransport, ImClient}
import polyglot.im.infrastructure.codec.JsonWireCodec

object ClientWiring {
  def create[F[_]: Async](
      transport: HttpTransport[F],
      tokens: TokenProvider[F],
      scope: AccountScope,
      deviceId: String,
      store: LocalStore[F],
      socket: SocketConnector[F],
      codec: WireCodec = JsonWireCodec
  ): F[ImClient[F]] =
    ImClientFactory.create(
      new ApiClient(new AuthenticatedTransport(transport, tokens), codec = codec),
      scope,
      deviceId,
      store,
      socket,
      codec
    )
}
