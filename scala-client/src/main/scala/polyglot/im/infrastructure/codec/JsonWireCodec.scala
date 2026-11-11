package polyglot.im.infrastructure.codec

/** JSON 消息编解码实现；HTTP 使用对象信封，实时连接使用独立同步帧。 */
object JsonWireCodec extends polyglot.im.ports.WireCodec {
  val contentType: String = "application/json"

  /** 将协议对象编码为传输格式，保留字符串序号与原始业务载荷。 */
  def encode(value: io.circe.Json): String = value.noSpaces

  /** 解析传输正文；格式或协议结构不合法时返回或抛出解码错误。 */
  def decode(value: String): Either[Throwable, io.circe.Json] = io.circe.parser.parse(value)
}
