package polyglot.im.server.config

/** HOCON 中的监听地址、端口及 HTTP 在途请求上限。 */
final case class HttpSettings(host: String, port: Int, maxInFlight: Int)
