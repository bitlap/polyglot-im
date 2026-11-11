package polyglot.im.server.common
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 协议使用的 Base64URL、摘要、HMAC 及常量时间比较工具。 */
object Crypto {

  /** 编码为不带填充的 Base64URL 文本。 */
  def b64(value: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding().encodeToString(value)

  /** 解码 Base64URL；输入编码不合法时拒绝。 */
  def un64(value: String): Array[Byte] = Base64.getUrlDecoder.decode(value)

  /** 计算 UTF-8 文本的 SHA-256 摘要并使用 Base64URL 表示。 */
  def digest(value: String): String = b64(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))

  /** 计算 UTF-8 文本的 HMAC-SHA256 签名，不输出原始密钥。 */
  def hmac(secret: String, value: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    b64(mac.doFinal(value.getBytes(UTF_8)))
  }

  /** 以常量时间比较签名文本，避免提前返回泄露匹配位置。 */
  def equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))
}
