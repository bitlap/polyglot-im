package polyglot.im.server.common
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
object Crypto {
  def b64(value: Array[Byte]): String  = Base64.getUrlEncoder.withoutPadding().encodeToString(value)
  def un64(value: String): Array[Byte] = Base64.getUrlDecoder.decode(value)
  def digest(value: String): String    = b64(MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)))
  def hmac(secret: String, value: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    b64(mac.doFinal(value.getBytes(UTF_8)))
  }
  def equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))
}
