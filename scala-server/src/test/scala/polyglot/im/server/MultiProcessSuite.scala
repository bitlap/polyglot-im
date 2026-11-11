package polyglot.im.server

import java.net.{ServerSocket, URI}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import munit.CatsEffectSuite
import polyglot.im.{ApiClient, NotificationSender}
import polyglot.im.transport.*

/** 两个真实 JVM，而非共享内存的两个 HTTP 端口。 */
class MultiProcessSuite extends CatsEffectSuite {
  override def munitIOTimeout = 120.seconds
  private val userSecret      = "u" * 32
  private val serviceSecret   = "s" * 32
  private def token(service: Boolean): String = {
    val head = Protocol.b64("{\"alg\":\"HS256\"}".getBytes(UTF_8))
    val claims = Json.obj(
      "iss"        -> Json.fromString("polyglot-im"),
      "aud"        -> Json.fromString("polyglot-im"),
      "app_id"     -> Json.fromString("multiprocess"),
      "sub"        -> Json.fromString(if (service) "producer" else "alice"),
      "role"       -> Json.fromString(if (service) "service" else "user"),
      "exp"        -> Json.fromLong(Protocol.now + 600),
      "types"      -> Json.arr(Json.fromString("*")),
      "recipients" -> Json.arr(Json.fromString("*"))
    )
    val body = head + "." + Protocol.b64(claims.noSpaces.getBytes(UTF_8))
    body + "." + Protocol.hmac(if (service) serviceSecret else userSecret, body)
  }
  private def node(directory: Path, name: String, pgPort: Int): Resource[IO, (Process, URI)] = for {
    port <- Resource.eval(IO.blocking {
      val socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
      try socket.getLocalPort
      finally socket.close()
    })
    file <- Resource.eval(
      IO.blocking(
        Files.writeString(
          directory.resolve(name + ".conf"),
          s"""
      polyglot-im {
        server { host="127.0.0.1", port=$port }
        database { url="jdbc:postgresql://127.0.0.1:$pgPort/postgres", user="postgres", pool-size=4 }
        auth { app-id="multiprocess", user-jwt-secret="$userSecret", service-jwt-secret="$serviceSecret", cursor-secret="${"c" * 32}" }
      }
      """
        )
      )
    )
    process <- Resource.make(IO.blocking {
      val builder = new ProcessBuilder(
        Path.of(sys.props("java.home"), "bin", "java").toString,
        "-Xmx256m",
        "-cp",
        sys.props("java.class.path"),
        "polyglot.im.server.Main",
        "--config",
        file.toString
      )
        .redirectErrorStream(true)
        .redirectOutput(directory.resolve(name + ".log").toFile)
      builder
        .environment()
        .keySet()
        .removeIf(key =>
          key.startsWith("POLYGLOT_IM_") || Set("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")(key)
        )
      builder.start()
    })(p => IO.blocking { p.destroyForcibly(); p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS); () })
    base = URI.create(s"http://127.0.0.1:$port")
    network <- JdkTransport.resource[IO](base)
    _ <- Resource.eval {
      def ready: IO[Unit] = network._1.execute(HttpRequest("GET", "/health/ready")).attempt.flatMap {
        case Right(response) if response.status == 200 => IO.unit
        case _                                         => IO.sleep(100.millis) *> IO.defer(ready)
      }
      ready.timeoutTo(
        30.seconds,
        IO.blocking(Files.readString(directory.resolve(name + ".log")))
          .flatMap(log => IO.raiseError(new IllegalStateException("child startup failed: " + log.takeRight(3000))))
      )
    }
  } yield (process, base)

  test("跨 JVM：A 签发票据、B 建 WS，A 写入唤醒 B；强杀后继续发送、大消息分页与登出断连") {
    val resources = for {
      pg <- Resource.make(IO.blocking(EmbeddedPostgres.builder().setPort(0).start()))(p => IO.blocking(p.close()))
      dir <- Resource.make(IO.blocking(Files.createTempDirectory("im-multiprocess")))(p =>
        IO.blocking {
          val paths = Files.walk(p)
          try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
          finally paths.close()
        }
      )
      a        <- node(dir, "a", pg.getPort)
      b        <- node(dir, "b", pg.getPort)
      networkA <- JdkTransport.resource[IO](a._2)
      networkB <- JdkTransport.resource[IO](b._2)
      api = (network: HttpTransport[IO], service: Boolean) =>
        new ApiClient[IO](
          new AuthenticatedTransport(
            network,
            new TokenProvider[IO] { def token: IO[String] = IO.pure(MultiProcessSuite.this.token(service)) }
          )
        )
      userA = api(networkA._1, false)
      userB = api(networkB._1, false)
      ticket <- Resource.eval(userA.request("POST", "/session", Some(Json.obj("device_id" -> Json.fromString("ios")))))
      ticketText = ticket.hcursor.get[String]("ticket").toOption.get
      socket <- networkB._2.connect(
        URI.create(b._2.toString.replace("http:", "ws:") + "/api/v1/im/ws?ticket=" + ticketText)
      )
    } yield (
      a._1,
      userB,
      new NotificationSender[IO](api(networkA._1, true)),
      new NotificationSender[IO](api(networkB._1, true)),
      socket,
      networkB._2,
      b._2
    )
    resources.use { case (processA, userB, senderA, senderB, socket, connector, baseB) =>
      def delivery(seq: String) = socket.messages
        .map(text => parse(text).toOption.get)
        .filter(_.hcursor.downField("data").get[String]("latest_seq").contains(seq))
        .take(1)
        .compile
        .lastOrError
        .timeout(10.seconds)
      for {
        received <- delivery("1").start
        first    <- senderA.send("alice", "stable-key", "system.notice", Json.obj("text" -> Json.fromString("跨进程消息")))
        _        <- received.joinWithNever
        _ <- IO.blocking {
          processA.destroyForcibly(); assert(processA.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        repeated <- senderB.send("alice", "stable-key", "system.notice", Json.obj("text" -> Json.fromString("跨进程消息")))
        _ = assertEquals(repeated.messageId, first.messageId)
        secondDelivery <- delivery("2").start
        _     <- senderB.send("alice", "after-crash", "system.notice", Json.obj("text" -> Json.fromString("故障后继续")))
        _     <- secondDelivery.joinWithNever
        state <- userB.request("GET", "/state")
        _ = assertEquals(state.hcursor.get[String]("latest_seq").toOption, Some("2"))
        _ <- userB.request("DELETE", "/devices/ios/session")
        _ <- socket.messages.compile.drain.attempt.timeout(7.seconds)
        _ <- (0 until 80).toList.traverse_(i =>
          senderB.send("alice", s"large-$i", "system.notice", Json.obj("blob" -> Json.fromString("x" * 14000)))
        )
        largeTicket <- userB.request("POST", "/session", Some(Json.obj("device_id" -> Json.fromString("large-ios"))))
        uri = URI.create(
          baseB.toString
            .replace("http:", "ws:") + "/api/v1/im/ws?ticket=" + largeTicket.hcursor.get[String]("ticket").toOption.get
        )
        _ <- connector.connect(uri).use { large =>
          for {
            frames <- large.messages.take(4).compile.toList.timeout(10.seconds)
            _ = assert(frames.forall(_.getBytes(UTF_8).length < 1048576))
            pages = frames
              .map(text => parse(text).toOption.get.hcursor.downField("data").get[List[Json]]("events").toOption.get)
            _ = assertEquals(pages.map(_.size), List(25, 25, 25, 7))
            _ <- userB.request("DELETE", "/devices/large-ios/session")
            _ <- large.messages.compile.drain.attempt.timeout(7.seconds)
          } yield ()
        }

      } yield ()
    }
  }
}
