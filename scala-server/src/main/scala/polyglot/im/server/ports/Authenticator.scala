package polyglot.im.server.ports

import polyglot.im.server.domain.Identity

trait Authenticator[F[_]] { def authenticate(token: String): F[Identity] }
