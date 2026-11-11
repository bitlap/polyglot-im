package polyglot.im.server.config
import com.comcast.ip4s.{Host, Port}

/** HTTP 监听地址与端口，使用已校验的 Host 和 Port。 */
final case class ServerConfig(host: Host, port: Port)
