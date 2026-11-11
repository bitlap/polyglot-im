package polyglot.im.server.config
import com.comcast.ip4s.{Host, Port}
final case class ServerConfig(host: Host, port: Port)

object ServerConfig {
  def fromEnvironment(env: Map[String, String]): Either[String, ServerConfig] =
    for {
      host       <- Host.fromString(env.getOrElse("POLYGLOT_IM_HOST", "127.0.0.1")).toRight("invalid POLYGLOT_IM_HOST")
      portNumber <- env.getOrElse("POLYGLOT_IM_PORT", "8090").toIntOption.toRight("invalid POLYGLOT_IM_PORT")
      port       <- Port.fromInt(portNumber).toRight("invalid POLYGLOT_IM_PORT")
    } yield ServerConfig(host, port)
}
