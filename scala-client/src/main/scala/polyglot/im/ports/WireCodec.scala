package polyglot.im.ports

/** HTTP 正文和实时帧的编解码端口，同一连接链路应使用同一实现。 */
trait WireCodec {

  /** 返回此编解码器产生的 HTTP 正文媒体类型。 */
  def contentType: String

  /** 将协议对象编码为传输格式，保留字符串序号与原始业务载荷。 */
  def encode(value: io.circe.Json): String

  /** 解析传输正文；格式或协议结构不合法时返回或抛出解码错误。 */
  def decode(value: String): Either[Throwable, io.circe.Json]
}
