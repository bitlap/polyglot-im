package polyglot.im.server

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{KeyFactory, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import scala.concurrent.duration.*
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import fs2.Stream
import io.circe.Json

trait PushProvider[F[_]]  { def send(target: PushTarget): F[PushResult]      }
trait ApnsTransport[F[_]] { def send(request: HttpRequest): F[(Int, String)] }
final case class ApnsConfig(team: String, keyId: String, topic: String, keyFile: Path, sandbox: Boolean)

/** APNs HTTP/2 + ES256；仅提供商接受，不生成设备送达或用户已读状态。 */
final class ApnsProvider[F[_]: Async](
    config: ApnsConfig,
    http: ApnsTransport[F],
    key: java.security.PrivateKey,
    cache: Ref[F, Option[(Long, String)]]
) extends PushProvider[F] {
  private def token: F[String] = cache.get.flatMap {
    case Some((until, value)) if until > Protocol.now => Async[F].pure(value)
    case _ =>
      Async[F]
        .blocking {
          val header = Protocol.b64(
            Json.obj("alg" -> Json.fromString("ES256"), "kid" -> Json.fromString(config.keyId)).noSpaces.getBytes(UTF_8)
          )
          val body = Protocol.b64(
            Json
              .obj("iss" -> Json.fromString(config.team), "iat" -> Json.fromLong(Protocol.now))
              .noSpaces
              .getBytes(UTF_8)
          )
          val input  = header + "." + body
          val signer = Signature.getInstance("SHA256withECDSAinP1363Format"); signer.initSign(key);
          signer.update(input.getBytes(UTF_8))
          input + "." + Protocol.b64(signer.sign())
        }
        .flatTap(value => cache.set(Some((Protocol.now + 1800, value))))
  }
  def send(target: PushTarget): F[PushResult] = token.flatMap { jwt =>
    val m     = target.message
    val text  = if (target.preview) m.payload.hcursor.get[String]("text").getOrElse("你有一条新消息").take(200) else "你有一条新消息"
    val title = if (target.preview) m.payload.hcursor.get[String]("title").getOrElse("消息提醒").take(80) else "消息提醒"
    val payload = Json.obj(
      "aps" -> Json.obj(
        "alert" -> Json.obj("title" -> Json.fromString(title), "body" -> Json.fromString(text)),
        "sound" -> Json.fromString("default"),
        "badge" -> Json.fromLong(target.unread)
      ),
      "app_id"     -> Json.fromString(m.app),
      "user_id"    -> Json.fromString(m.user),
      "message_id" -> Json.fromString(m.id)
    )
    val host = if (config.sandbox) "api.sandbox.push.apple.com" else "api.push.apple.com"
    val request = HttpRequest
      .newBuilder(URI.create(s"https://$host/3/device/${target.token}"))
      .version(HttpClient.Version.HTTP_2)
      .timeout(Duration.ofSeconds(10))
      .header("authorization", "bearer " + jwt)
      .header("apns-topic", config.topic)
      .header("apns-push-type", "alert")
      .header("apns-priority", "10")
      .header("apns-collapse-id", m.id)
      .header("apns-expiration", (Protocol.now + 86400).toString)
      .POST(HttpRequest.BodyPublishers.ofString(payload.noSpaces))
      .build()
    http
      .send(request)
      .map { case (status, responseBody) =>
        if (status == 200) PushResult(false)
        else if (
          status == 410 || responseBody.contains("BadDeviceToken") || responseBody.contains("DeviceTokenNotForTopic")
        ) PushResult(false, true, "device token rejected")
        else PushResult(true, false, s"APNs HTTP $status")
      }
      .handleError(_ => PushResult(true, false, "APNs transport error"))
  }
}
object ApnsProvider {
  def resource[F[_]: Async](config: ApnsConfig): Resource[F, PushProvider[F]] = for {
    executor <- Resource.make(Async[F].delay(java.util.concurrent.Executors.newFixedThreadPool(4)))(e =>
      Async[F].blocking { e.shutdown(); e.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); () }
    )
    http <- Resource.eval(
      Async[F].delay(
        HttpClient
          .newBuilder()
          .version(HttpClient.Version.HTTP_2)
          .executor(executor)
          .connectTimeout(Duration.ofSeconds(10))
          .build()
      )
    )
    key <- Resource.eval(Async[F].blocking {
      val pem = Files
        .readString(config.keyFile)
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replaceAll("\\s", "")
      KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder.decode(pem)))
    })
    cache <- Resource.eval(Ref.of[F, Option[(Long, String)]](None))
  } yield new ApnsProvider[F](
    config,
    new ApnsTransport[F] {
      def send(request: HttpRequest): F[(Int, String)] = Async[F]
        .fromCompletableFuture(Async[F].delay(http.sendAsync(request, HttpResponse.BodyHandlers.ofString())))
        .map(r => (r.statusCode(), r.body()))
    },
    key,
    cache
  )
}
object PushWorker {
  def run[F[_]: Async](store: Store[F], provider: PushProvider[F]): F[Unit] = Stream
    .awakeEvery[F](1.second)
    .evalMap(_ =>
      store.claim.handleErrorWith(e =>
        Async[F]
          .delay(org.slf4j.LoggerFactory.getLogger("polyglot.im.push").error("Outbox claim failed", e))
          .as(List.empty[PushJob])
      )
    )
    .flatMap(Stream.emits)
    .evalMap { job =>
      store
        .pushTarget(job)
        .flatMap {
          case None => store.finish(job, PushResult(false), None)
          case Some(target) =>
            provider
              .send(target)
              .handleError(_ => PushResult(true, false, "provider failure"))
              .flatMap(result => store.finish(job, result, Some(target.token)))
        }
        .handleErrorWith(e =>
          Async[F].delay(
            org.slf4j.LoggerFactory.getLogger("polyglot.im.push").error("Outbox job failed; lease will expire", e)
          )
        )
    }
    .compile
    .drain
}
