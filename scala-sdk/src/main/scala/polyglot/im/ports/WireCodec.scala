package polyglot.im.ports

trait WireCodec {
  def contentType: String
  def encode(value: io.circe.Json): String
  def decode(value: String): Either[Throwable, io.circe.Json]
}
