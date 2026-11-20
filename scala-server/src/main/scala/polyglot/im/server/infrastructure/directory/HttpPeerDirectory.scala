package polyglot.im.server.infrastructure.directory

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import polyglot.im.server.domain.{ApiError, Identity, PeerRelationship, Protocol}
import polyglot.im.server.domain.ApiError.{Code, Message}
import polyglot.im.server.ports.PeerDirectory
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.{CompletionStage, Flow}

/** 向固定可信业务目录逐次核对身份与关系；无授权缓存，不跟随重定向，不保存 Bearer。 */
final class HttpPeerDirectory[F[_]: Async](uri: URI, http: HttpClient) extends PeerDirectory[F] {
  require(
    uri.getHost != null && uri.getUserInfo == null && uri.getQuery == null && uri.getFragment == null,
    Message.InvalidDirectDirectory
  )
  require(
    uri.getScheme == "https" || uri.getScheme == "http" && Set("127.0.0.1", "localhost", "::1")(uri.getHost),
    Message.InvalidDirectDirectory
  )
  require(http.followRedirects() == HttpClient.Redirect.NEVER, Message.InvalidDirectDirectory)

  /** 一次有界请求最多核对 50 人；身份不匹配、超时、过大响应和目录错误一律失败关闭。 */
  def relationships(who: Identity, peers: List[String], credential: String): F[Map[String, PeerRelationship]] = {
    val unavailable = ApiError(503, Code.DirectUnavailable, Message.DirectUnavailable)
    if (peers.isEmpty || peers.distinct.size != peers.size || peers.size > 50 || credential.isEmpty)
      Async[F].raiseError(unavailable)
    else {
      val request = HttpRequest
        .newBuilder(uri)
        .timeout(Duration.ofSeconds(5))
        .header("Authorization", "Bearer " + credential)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(
          HttpRequest.BodyPublishers
            .ofString(Json.obj("user_ids" -> Json.arr(peers.map(Json.fromString)*)).noSpaces, UTF_8)
        )
        .build()
      Async[F]
        .fromCompletableFuture(
          Async[F].delay(
            http.sendAsync(
              request,
              _ => {
                val target = HttpResponse.BodySubscribers.ofByteArray()
                new HttpResponse.BodySubscriber[Array[Byte]] {
                  private var subscription: Flow.Subscription     = null
                  private var size                                = 0L
                  def getBody(): CompletionStage[Array[Byte]]     = target.getBody()
                  def onSubscribe(value: Flow.Subscription): Unit = { subscription = value; target.onSubscribe(value) }
                  def onNext(chunks: java.util.List[ByteBuffer]): Unit = {
                    val values = chunks.iterator(); while (values.hasNext) size += values.next().remaining()
                    if (size > 65536) { subscription.cancel(); target.onError(unavailable) }
                    else target.onNext(chunks)
                  }
                  def onError(error: Throwable): Unit = target.onError(error)
                  def onComplete(): Unit              = target.onComplete()
                }
              }
            )
          )
        )
        .flatMap { response =>
          Async[F].delay {
            if (response.statusCode() != 200) throw unavailable
            val json = parse(new String(response.body(), UTF_8)).toOption.getOrElse(throw unavailable)
            val data = json.hcursor.downField("data").focus.getOrElse(json)
            if (
              !data.hcursor.get[Boolean]("active").getOrElse(false) || Protocol.field(
                data,
                "app_id"
              ) != who.app || Protocol.field(data, "user_id") != who.user
            ) throw unavailable
            val items = data.hcursor.get[List[Json]]("items").getOrElse(throw unavailable)
            if (items.size > 50) throw unavailable
            val result = items.map { item =>
              val id = Protocol.field(item, "user_id")
              if (!peers.contains(id)) throw unavailable
              val cursor = item.hcursor
              id -> PeerRelationship(
                cursor.get[Boolean]("exists").getOrElse(false),
                cursor.get[Boolean]("following").getOrElse(false),
                cursor.get[Boolean]("follows_you").getOrElse(false)
              )
            }.toMap
            if (result.size != items.size) throw unavailable
            peers.map(id => id -> result.getOrElse(id, PeerRelationship(false, false, false))).toMap
          }
        }
        .handleErrorWith(_ => Async[F].raiseError(unavailable))
    }
  }
}

/** 仅在配置开启时持有有界执行器和 HTTP 客户端，退出时释放自有资源。 */
object HttpPeerDirectory {

  /** 为固定目录创建独立资源，不复用其他项目数据库或私有配置。 */
  def resource[F[_]: Async](uri: URI): Resource[F, PeerDirectory[F]] = for {
    executor <- Resource.make(Async[F].delay(java.util.concurrent.Executors.newFixedThreadPool(4)))(e =>
      Async[F].blocking { e.shutdown(); e.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS); () }
    )
    http <- Resource.eval(
      Async[F].delay(
        HttpClient
          .newBuilder()
          .executor(executor)
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build()
      )
    )
  } yield new HttpPeerDirectory[F](uri, http)
}
