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

final class JdkTransport[F[_]: Async](
    base: URI,
    client: HttpClient,
    codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
) extends HttpTransport[F] {
  require(Set("http", "https")(base.getScheme) && base.getHost != null, "absolute HTTP(S) base URL required")
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
final class JdkSocketConnector[F[_]: Async](
    client: HttpClient,
    codec: WireCodec = polyglot.im.infrastructure.codec.JsonWireCodec
) extends SocketConnector[F] {
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
            private val buffer                       = new StringBuilder
            override def onOpen(ws: WebSocket): Unit = ws.request(1)
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
            override def onPing(ws: WebSocket, message: java.nio.ByteBuffer): CompletionStage[?] = {
              ws.request(1); ws.sendPong(message)
            }
            override def onPong(ws: WebSocket, message: java.nio.ByteBuffer): CompletionStage[?] = {
              ws.request(1); CompletableFuture.completedFuture(null)
            }
            override def onClose(ws: WebSocket, status: Int, reason: String): CompletionStage[?] =
              dispatcher.unsafeToCompletableFuture(channel.close.void).thenApply(_ => null)
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
    def messages: Stream[F, String] = channel.stream ++ Stream
      .eval(failure.get)
      .flatMap(_.fold(Stream.empty.covaryAll[F, String])(Stream.raiseError[F]))
    def ack(cursor: String): F[Unit] = Async[F]
      .fromCompletableFuture(
        Async[F]
          .delay(socket.sendText(codec.encode(io.circe.Json.obj("cursor" -> io.circe.Json.fromString(cursor))), true))
      )
      .void
  }
}
object JdkTransport {
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
