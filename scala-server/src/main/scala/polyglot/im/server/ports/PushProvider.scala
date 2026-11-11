package polyglot.im.server.ports

import polyglot.im.server.domain.PushResult
import polyglot.im.server.domain.PushTarget

trait PushProvider[F[_]] { def send(target: PushTarget): F[PushResult] }
