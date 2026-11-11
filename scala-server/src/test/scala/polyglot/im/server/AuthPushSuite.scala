package polyglot.im.server

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.{KeyPairGenerator, Signature}
import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite

class AuthPushSuite extends CatsEffectSuite {
  private val userKey    = "u" * 32
  private val serviceKey = "s" * 32
  private def jwt(role: String, key: String, expiry: Long = Protocol.now + 300): String = {
    val head = Protocol.b64("{\"alg\":\"HS256\"}".getBytes(UTF_8))
    val body = Protocol.b64(
      Json
        .obj(
          "iss"    -> Json.fromString("issuer"),
          "aud"    -> Json.fromString("polyglot-im"),
          "app_id" -> Json.fromString("app"),
          "sub"    -> Json.fromString("alice"),
          "role"   -> Json.fromString(role),
          "exp"    -> Json.fromLong(expiry)
        )
        .noSpaces
        .getBytes(UTF_8)
    )
    val content = head + "." + body
    content + "." + Protocol.hmac(key, content)
  }
  test("用户与生产者签名密钥隔离、过期、篡改和游标用户隔离") {
    val auth = new JwtAuthenticator[IO](userKey, serviceKey, "issuer", "app")
    for {
      who <- auth.authenticate(jwt("user", userKey))
      _ = assertEquals(who.user, "alice")
      wrong    <- auth.authenticate(jwt("service", userKey)).attempt
      expired  <- auth.authenticate(jwt("user", userKey, Protocol.now - 1)).attempt
      tampered <- auth.authenticate(jwt("user", userKey) + "x").attempt
      _      = assert(wrong.isLeft && expired.isLeft && tampered.isLeft)
      codec  = new CursorCodec("c" * 32)
      cursor = codec.encode(who, 99)
      _      = assertEquals(codec.decode(who, Some(cursor)), 99L)
      _      = intercept[ApiError](codec.decode(who.copy(user = "bob"), Some(cursor)))
    } yield ()
  }
  test("APNs ES256 原始签名、HTTP/2 请求、失效 token 与重试分类") {
    val generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1")); val key = generator.generateKeyPair()
    for {
      cache    <- Ref.of[IO, Option[(Long, String)]](None)
      refresh  <- Semaphore[IO](1)
      code     <- Ref.of[IO, (Int, String)]((200, ""))
      requests <- Ref.of[IO, List[java.net.http.HttpRequest]](Nil)
      transport = new ApnsTransport[IO] {
        def send(request: java.net.http.HttpRequest): IO[(Int, String)] = requests.update(request :: _) *> code.get
      }
      provider = new ApnsProvider[IO](
        ApnsConfig("team", "key", "app.topic", Path.of("unused"), true),
        transport,
        key.getPrivate,
        cache,
        refresh
      )
      message = StoredMessage(
        "message",
        "app",
        "alice",
        1,
        "task.completed",
        Json.obj("text" -> Json.fromString("sensitive")),
        Protocol.time
      )
      target = PushTarget("a" * 64, false, 1, message)
      accepted <- List.fill(16)(()).parTraverse(_ => provider.send(target))
      _ = assert(accepted.forall(result => !result.retry && !result.invalidToken))
      _       <- code.set((410, "{\"reason\":\"Unregistered\"}"))
      invalid <- provider.send(target)
      _ = assert(invalid.invalidToken)
      _     <- code.set((503, ""))
      retry <- provider.send(target)
      _ = assert(retry.retry)
      sent <- requests.get
      _        = assertEquals(sent.head.uri().getHost, "api.sandbox.push.apple.com")
      token    = sent.head.headers().firstValue("authorization").get().stripPrefix("bearer ")
      parts    = token.split("\\.")
      raw      = Protocol.un64(parts(2))
      _        = assertEquals(raw.length, 64)
      verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
      _        = verifier.initVerify(key.getPublic)
      _        = verifier.update((parts(0) + "." + parts(1)).getBytes(UTF_8))
      _        = assert(verifier.verify(raw))
      _        = assertEquals(sent.map(_.headers().firstValue("authorization").get()).distinct.size, 1)
    } yield ()
  }
}
