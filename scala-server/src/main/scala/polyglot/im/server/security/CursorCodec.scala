package polyglot.im.server.security

import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import io.circe.{Json, Printer}
import io.circe.parser.parse

final class CursorCodec(secret: String) {
  require(secret.getBytes(UTF_8).length >= 32, "cursor signing secret must be at least 32 bytes")
  def encode(who: Identity, value: Long): String = {
    val body = Protocol.b64(
      Json
        .obj("a" -> Json.fromString(who.app), "u" -> Json.fromString(who.user), "n" -> Json.fromString(value.toString))
        .noSpaces
        .getBytes(UTF_8)
    )
    body + "." + Protocol.hmac(secret, body)
  }
  def decode(who: Identity, value: Option[String]): Long = value
    .filter(_.nonEmpty)
    .map { token =>
      try {
        val parts = token.split("\\.", -1)
        if (parts.length != 2 || !Protocol.equal(Protocol.hmac(secret, parts(0)), parts(1)))
          throw new IllegalArgumentException()
        val json = parse(new String(Protocol.un64(parts(0)), UTF_8)).toOption.get
        if (Protocol.field(json, "a") != who.app || Protocol.field(json, "u") != who.user)
          throw new IllegalArgumentException()
        Protocol.number(Protocol.field(json, "n"))
      } catch { case _: Exception => throw ApiError(400, "INVALID_CURSOR", "cursor does not belong to this account") }
    }
    .getOrElse(0L)
}
