package polyglot.im.ports
import fs2.Stream
import java.net.URI

trait SocketSession[F[_]]   { def messages: Stream[F, String]; def ack(cursor: String): F[Unit] }
trait SocketConnector[F[_]] { def connect(uri: URI): cats.effect.Resource[F, SocketSession[F]]  }
