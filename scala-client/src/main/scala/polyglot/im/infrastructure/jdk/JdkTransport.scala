package polyglot.im.infrastructure.jdk

import java.net.URI
import java.net.http.{HttpClient, WebSocket, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration
import java.util.concurrent.{CompletionStage, CompletableFuture}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import cats.effect.syntax.all.*
import fs2.Stream
import fs2.concurrent.Channel
import polyglot.im.ports.*

/** 借用 JDK HTTP 客户端发送请求，以注入的编解码器处理正文。 */
final class JdkTransport[F[_]: Async](
    base: URI,
    client: HttpClient,
    codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
) extends HttpTransport[F] {
  require(Set("http", "https")(base.getScheme) && base.getHost != null, "absolute HTTP(S) base URL required")

  /** 执行一次传输并保留真实 HTTP 状态；不在此层推断消息送达或已读。 */
  def execute(request: HttpRequest): F[HttpResponse] = {
    val builder =
      JRequest.newBuilder(URI.create(base.toString.stripSuffix("/") + request.path)).timeout(Duration.ofSeconds(15))
    request.headers.foreach { case (k, v) => builder.header(k, v) }
    val publisher =
      request.body.fold(JRequest.BodyPublishers.noBody())(j => JRequest.BodyPublishers.ofString(codec.encode(j)))
    if (request.body.nonEmpty) builder.header("Content-Type", codec.contentType)
    val built = builder.method(request.method, publisher).build()
    Async[F]
      .fromCompletableFuture(Async[F].delay(client.sendAsync(built, JResponse.BodyHandlers.ofString())))
      .map(r =>
        HttpResponse(
          r.statusCode(),
          r.body(),
          r.headers().map().asScala.map { case (k, v) => k -> v.asScala.mkString(",") }.toMap
        )
      )
  }
}

/** 将 JDK WebSocket 转换为受 Resource 管理的文本帧会话。 */
final class JdkSocketConnector[F[_]: Async](
    client: HttpClient,
    codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
) extends SocketConnector[F] {

  /** 建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。 */
  def connect(uri: URI): Resource[F, SocketSession[F]] = for {
    dispatcher <- Dispatcher.parallel[F]
    channel    <- Resource.eval(Channel.bounded[F, String](4))
    failure    <- Resource.eval(Ref.of[F, Option[Throwable]](None))
    socket <- Resource.make(Async[F].fromCompletableFuture(Async[F].delay {
      client
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .buildAsync(
          uri,
          new WebSocket.Listener {
            private val buffer = new StringBuilder

            /** 连接建立后请求首个入站帧，启动受背压控制的读取。 */
            override def onOpen(ws: WebSocket): Unit = ws.request(1)

            /** 拼接分片文本帧并转交消息流，再请求下一帧。 */
            override def onText(ws: WebSocket, text: CharSequence, last: Boolean): CompletionStage[?] = {
              buffer.append(text)
              if (buffer.length > 1048576) {
                ws.abort();
                dispatcher.unsafeRunAndForget(
                  failure.set(Some(new IllegalArgumentException("frame too large"))) *> channel.close.void
                ); CompletableFuture.completedFuture(null)
              } else if (last) {
                val value = buffer.toString; buffer.clear();
                dispatcher
                  .unsafeToCompletableFuture(channel.send(value).void *> Async[F].delay(ws.request(1)))
                  .thenApply(_ => null)
              } else { ws.request(1); CompletableFuture.completedFuture(null) }
            }

            /** 响应连接保活帧并继续接收入站数据。 */
            override def onPing(ws: WebSocket, message: java.nio.ByteBuffer): CompletionStage[?] = {
              ws.request(1); ws.sendPong(message)
            }

            /** 接收保活响应并继续请求下一帧。 */
            override def onPong(ws: WebSocket, message: java.nio.ByteBuffer): CompletionStage[?] = {
              ws.request(1); CompletableFuture.completedFuture(null)
            }

            /** 结束入站消息流并记录连接关闭状态。 */
            override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
              dispatcher.unsafeToCompletableFuture(channel.close.void).thenApply(_ => null)

            /** 将连接错误传递给消息流，由同步层处理重连。 */
            override def onError(ws: WebSocket, error: Throwable): Unit =
              dispatcher.unsafeRunAndForget(failure.set(Some(error)) *> channel.close.void)
          }
        )
    })) { ws =>
      Async[F]
        .fromCompletableFuture(Async[F].delay(ws.sendClose(1000, "closing")))
        .void
        .timeoutTo(3.seconds, Async[F].unit)
        .handleError(_ => ())
        .guarantee(Async[F].delay(ws.abort()))
    }
  } yield new SocketSession[F] {

    /** 读取当前会话的入站消息；流结束或失败表示需要由同步层恢复连接。 */
    def messages: Stream[F, String] = channel.stream ++ Stream
      .eval(failure.get)
      .flatMap(_.fold(Stream.empty.covaryAll[F, String])(Stream.raiseError[F]))

    /** 确认已完成本地提交的接收位置，不推进用户阅读水位。 */
    def ack(cursor: String): F[Unit] = Async[F]
      .fromCompletableFuture(
        Async[F]
          .delay(socket.sendText(codec.encode(io.circe.Json.obj("cursor" -> io.circe.Json.fromString(cursor))), true))
      )
      .void
  }
}

/** 创建配套的 JDK HTTP 与 WebSocket 适配器，并统一管理执行器生命周期。 */
object JdkTransport {

  /** 创建 JDK HTTP/WS 适配器及执行器，作用域退出时关闭自有资源。 */
  def resource[F[_]: Async](
      base: URI,
      codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
  ): Resource[F, (HttpTransport[F], SocketConnector[F])] = for {
    executor <- Resource.make(Async[F].delay(java.util.concurrent.Executors.newFixedThreadPool(4)))(e =>
      Async[F].blocking { e.shutdown(); e.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS); () }
    )
    client <- Resource.eval(
      Async[F].delay(
        HttpClient
          .newBuilder()
          .executor(executor)
          .connectTimeout(Duration.ofSeconds(15))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build()
      )
    )
  } yield (new JdkTransport[F](base, client, codec), new JdkSocketConnector[F](client, codec))
}
