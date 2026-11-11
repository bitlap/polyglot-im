package polyglot.im.infrastructure.codec

object JsonWireCodec extends polyglot.im.ports.WireCodec {
  val contentType: String                                     = "application/json"
  def encode(value: io.circe.Json): String                    = value.noSpaces
  def decode(value: String): Either[Throwable, io.circe.Json] = io.circe.parser.parse(value)
}
