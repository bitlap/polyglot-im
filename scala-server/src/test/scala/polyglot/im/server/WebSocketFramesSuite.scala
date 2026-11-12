package polyglot.im.server

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.websocket.WebSocketFrame
import polyglot.im.server.api.http.Server
import polyglot.im.server.config.ServerConfig

/** 在真实 TCP 连接上并发发送数据与自动 Pong，防止依赖升级重新引入帧字节交错。 */
class WebSocketFramesSuite extends CatsEffectSuite {
  test("自动 Pong 与数据下行并发时，文本内容完整且所有 Ping 都收到 Pong") {
    val payload = "x" * 8192
    val texts   = new AtomicInteger()
    val pongs   = new AtomicInteger()
    val failure = new CompletableFuture[Unit]()
    val listener = new WebSocket.Listener {
      private val buffer = new StringBuilder

      /** 拼接 JDK 回调的文本片段，核验完整消息未混入控制帧字节。 */
      override def onText(socket: WebSocket, text: CharSequence, last: Boolean): CompletionStage[?] = {
        buffer.append(text)
        if (last) {
          if (buffer.toString != payload) failure.completeExceptionally(new IllegalStateException("corrupted text"))
          texts.incrementAndGet()
          buffer.clear()
        }
        socket.request(1)
        CompletableFuture.completedFuture(null)
      }

      /** 统计已收到的 Pong 并继续读取后续帧。 */
      override def onPong(socket: WebSocket, data: ByteBuffer): CompletionStage[?] = {
        pongs.incrementAndGet()
        socket.request(1)
        CompletableFuture.completedFuture(null)
      }

      /** 将协议解析或连接错误传回测试，避免只等待业务计数超时。 */
      override def onError(socket: WebSocket, error: Throwable): Unit = { failure.completeExceptionally(error); () }
    }
    val resources = for {
      server <- Server.websocketResource[IO](
        ServerConfig(Host.fromString("127.0.0.1").get, Port.fromInt(0).get),
        ws =>
          HttpRoutes
            .of[IO] { case GET -> Root / "frames" =>
              ws.build(Stream.awakeEvery[IO](1.millis).as(WebSocketFrame.Text(payload)), _.drain)
            }
            .orNotFound
      )
      client <- Resource.fromAutoCloseable(IO(HttpClient.newHttpClient()))
      socket <- Resource.make(IO.fromCompletableFuture(IO {
        client
          .newWebSocketBuilder()
          .buildAsync(URI.create(s"ws://127.0.0.1:${server.address.getPort}/frames"), listener)
      }))(socket => IO(socket.abort()))
    } yield socket
    resources.use { socket =>
      def awaitFrames: IO[Unit] = IO.defer {
        if (pongs.get() == 500 && texts.get() >= 100) IO.unit
        else IO.sleep(10.millis) *> awaitFrames
      }
      val exercise = for {
        _ <- (0 until 500).toList.traverse_ { _ =>
          IO.fromCompletableFuture(IO(socket.sendPing(ByteBuffer.wrap(Array[Byte](1))))) *> IO.sleep(1.millis)
        }
        _ <- awaitFrames
      } yield ()
      IO.race(IO.fromCompletableFuture(IO.pure(failure)), exercise)
        .flatMap {
          case Left(_)  => IO.raiseError(new IllegalStateException("unexpected listener completion"))
          case Right(_) => IO.unit
        }
        .timeout(10.seconds)
    }
  }
}
