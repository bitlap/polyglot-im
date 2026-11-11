package polyglot.im.transport

/** 0.1.x 源码兼容导出。 */
type AuthenticatedTransport[F[_]] = polyglot.im.service.AuthenticatedTransport[F]
type HttpRequest                  = polyglot.im.ports.HttpRequest
lazy val HttpRequest = polyglot.im.ports.HttpRequest
type HttpResponse = polyglot.im.ports.HttpResponse
lazy val HttpResponse = polyglot.im.ports.HttpResponse
type HttpTransport[F[_]]      = polyglot.im.ports.HttpTransport[F]
type JdkSocketConnector[F[_]] = polyglot.im.infrastructure.jdk.JdkSocketConnector[F]
type JdkTransport[F[_]]       = polyglot.im.infrastructure.jdk.JdkTransport[F]
lazy val JdkTransport = polyglot.im.infrastructure.jdk.JdkTransport
type TokenProvider[F[_]] = polyglot.im.ports.TokenProvider[F]
