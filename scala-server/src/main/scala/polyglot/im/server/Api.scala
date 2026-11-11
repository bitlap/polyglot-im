package polyglot.im.server

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpApp, HttpRoutes, Request, Response, Status}
import org.http4s.circe.*
import org.http4s.dsl.Http4sDsl
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame

final case class RateLimits(userPerMinute: Int = 600, servicePerMinute: Int = 120) {
  require(userPerMinute > 0 && servicePerMinute > 0, "rate limits must be positive")
}

final class Api[F[_]: Async](
    store: Store[F],
    auth: Authenticator[F],
    cursors: CursorCodec,
    limiter: RateLimiter[F],
    rateLimits: RateLimits = RateLimits(),
    changes: Option[ChangeFeed[F]] = None
) {
  private val dsl = new Http4sDsl[F] {}; import dsl.*
  private def json(status: Int, value: Json): F[Response[F]] =
    Async[F].pure(Response[F](Status.fromInt(status).toOption.get).withEntity(value))
  private def fail(error: Throwable): F[Response[F]] = {
    val value = error match { case e: ApiError => e; case _ => ApiError(500, "INTERNAL_ERROR", "request failed") }
    json(
      value.status,
      Json.obj(
        "code"       -> Json.fromString(value.code),
        "message"    -> Json.fromString(value.message),
        "request_id" -> Json.fromString(Protocol.id),
        "retryable"  -> Json.fromBoolean(value.status >= 500 || value.status == 429)
      )
    )
  }
  private def authorized(req: Request[F], role: String)(run: Identity => F[Response[F]]): F[Response[F]] = {
    val token = req.headers.headers
      .find(_.name.toString.equalsIgnoreCase("Authorization"))
      .map(_.value)
      .filter(_.startsWith("Bearer "))
      .map(_.drop(7))
      .getOrElse("")
    (for {
      who <- auth.authenticate(token)
      _ <- {
        def expected(name: String) = req.headers.headers
          .find(_.name.toString.equalsIgnoreCase(name))
          .map(h => java.net.URLDecoder.decode(h.value, UTF_8))
        if (expected("X-IM-App-ID").exists(_ != who.app) || expected("X-IM-User-ID").exists(_ != who.user))
          Async[F].raiseError(ApiError(403, "IDENTITY_MISMATCH", "credential differs from expected account"))
        else Async[F].unit
      }
      _ <-
        if (who.role == role) Async[F].unit
        else Async[F].raiseError(ApiError(403, "FORBIDDEN", "credential role is not permitted"))
      accepted <- limiter.allow(who, if (role == "service") rateLimits.servicePerMinute else rateLimits.userPerMinute)
      _ <- if (accepted) Async[F].unit else Async[F].raiseError(ApiError(429, "RATE_LIMITED", "retry after one minute"))
      response <- run(who)
    } yield response).handleErrorWith(fail)
  }
  private def body(req: Request[F]): F[Json] = req.body.take(65537).compile.to(Array).flatMap { bytes =>
    if (bytes.length > 65536) Async[F].raiseError(ApiError(413, "PAYLOAD_TOO_LARGE", "request exceeds 64 KiB"))
    else
      Async[F].fromEither(
        parse(new String(bytes, UTF_8)).leftMap(_ => ApiError(400, "INVALID_ARGUMENT", "invalid JSON"))
      )
  }
  private def size(req: Request[F]): Int =
    req.uri.query.params.get("limit").map(Protocol.number).getOrElse(50L).max(1).min(100).toInt
  private def position(who: Identity, pos: Position): Json = Json.obj(
    "event_position"    -> Json.fromString(pos.cursor.toString),
    "cursor"            -> Json.fromString(cursors.encode(who, pos.cursor)),
    "latest_seq"        -> Json.fromString(pos.seq.toString),
    "read_seq"          -> Json.fromString(pos.read.toString),
    "unread_count"      -> Json.fromString(pos.unread.toString),
    "chat_unread_count" -> Json.fromString(pos.directUnread.toString),
    "conversation_id"   -> Json.fromString(Protocol.conversation(who.app, who.user))
  )
  private def sync(who: Identity, from: Long, limit: Int, device: Option[String] = None): F[Json] =
    device.fold(store.sync(who, from, limit))(id => store.syncDevice(who, id, from, limit)).map {
      case (pos, events, more) =>
        val next = events.lastOption.fold(from)(_._1)
        position(who, pos).deepMerge(
          Json.obj(
            "event_position" -> Json.fromString(next.toString),
            "cursor"         -> Json.fromString(cursors.encode(who, next)),
            "has_more"       -> Json.fromBoolean(more),
            "events" -> Json.arr(events.map { case (n, event) =>
              event.deepMerge(Json.obj("cursor" -> Json.fromString(cursors.encode(who, n))))
            }*)
          )
        )
    }
  def routes(ws: WebSocketBuilder2[F]): HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "api" / "v1" / "im" / "notifications" =>
      authorized(req, "service") { who =>
        for {
          data <- body(req); command <- Async[F].delay(Protocol.notification(data))
          _ <-
            if (
              (who.types("*") || who.types(command.kind)) && (who.recipients("*") || who.recipients(command.recipient))
            ) Async[F].unit
            else Async[F].raiseError(ApiError(403, "FORBIDDEN", "recipient or type is outside service scope"))
          result <- store.send(who, command); response <- json(if (result._2) 201 else 200, result._1.json)
        } yield response
      }
    case req @ POST -> Root / "api" / "v1" / "im" / "conversations" / "direct" =>
      authorized(req, "user") { who =>
        body(req).flatMap(data => store.createDirect(who, Protocol.field(data, "peer_user_id"))).flatMap(json(200, _))
      }
    case req @ GET -> Root / "api" / "v1" / "im" / "conversations" =>
      authorized(req, "user")(who =>
        store.conversations(who, req.uri.query.params.getOrElse("after", ""), size(req)).flatMap(json(200, _))
      )
    case req @ GET -> Root / "api" / "v1" / "im" / "conversations" / id / "messages" =>
      authorized(req, "user") { who =>
        store
          .directHistory(
            who,
            id,
            req.uri.query.params.get("before_seq").map(Protocol.number).getOrElse(Long.MaxValue),
            size(req)
          )
          .flatMap(json(200, _))
      }
    case req @ POST -> Root / "api" / "v1" / "im" / "conversations" / id / "messages" =>
      authorized(req, "user") { who =>
        body(req).flatMap(store.sendDirect(who, id, _)).flatMap { case (m, created) =>
          json(if (created) 201 else 200, m.json)
        }
      }
    case req @ PUT -> Root / "api" / "v1" / "im" / "conversations" / id / "read" =>
      authorized(req, "user") { who =>
        body(req)
          .flatMap(data => store.readDirect(who, id, Protocol.number(Protocol.field(data, "up_to_seq"))))
          .flatMap(json(200, _))
      }
    case req @ GET -> Root / "api" / "v1" / "im" / "messages" =>
      authorized(req, "user") { who =>
        val before = req.uri.query.params.get("before_seq").map(Protocol.number).getOrElse(Long.MaxValue)
        store.history(who, before, size(req)).flatMap { case (rows, more) =>
          json(
            200,
            Json.obj(
              "items"           -> Json.arr(rows.map(_.json)*),
              "has_more"        -> Json.fromBoolean(more),
              "next_before_seq" -> rows.lastOption.fold(Json.Null)(m => Json.fromString(m.seq.toString))
            )
          )
        }
      }
    case req @ GET -> Root / "api" / "v1" / "im" / "messages" / id =>
      authorized(req, "user")(who => store.get(who, id).flatMap(m => json(200, m.json)))
    case req @ GET -> Root / "api" / "v1" / "im" / "state" =>
      authorized(req, "user")(who => store.position(who).flatMap(p => json(200, position(who, p))))
    case req @ GET -> Root / "api" / "v1" / "im" / "snapshot" =>
      authorized(req, "user")(who =>
        store.snapshotPage(who).flatMap { case (pos, rows, conversations, more) =>
          json(
            200,
            position(who, pos).deepMerge(
              Json.obj(
                "messages"               -> Json.arr(rows.map(_.json)*),
                "conversations"          -> Json.arr(conversations*),
                "conversations_has_more" -> Json.fromBoolean(more)
              )
            )
          )
        }
      )
    case req @ GET -> Root / "api" / "v1" / "im" / "sync" =>
      authorized(req, "user") { who =>
        Async[F]
          .delay(cursors.decode(who, req.uri.query.params.get("cursor")))
          .flatMap(from => sync(who, from, size(req)))
          .flatMap(json(200, _))
      }
    case req @ PUT -> Root / "api" / "v1" / "im" / "read" =>
      authorized(req, "user") { who =>
        for {
          data <- body(req); n                 <- Async[F].delay(Protocol.number(Protocol.field(data, "up_to_seq")));
          pos  <- store.read(who, n); response <- json(200, position(who, pos))
        } yield response
      }
    case req @ POST -> Root / "api" / "v1" / "im" / "session" =>
      authorized(req, "user") { who =>
        for {
          data <- body(req); device <- Async[F].delay(Protocol.bounded(Protocol.field(data, "device_id"), "device_id"));
          _ <- store.register(who, device); ticket <- store.ticket(who, device)
          response <- json(
            200,
            Json.obj(
              "ticket"         -> Json.fromString(ticket),
              "app_id"         -> Json.fromString(who.app),
              "user_id"        -> Json.fromString(who.user),
              "expires_at"     -> Json.fromLong(who.expires),
              "websocket_path" -> Json.fromString("/api/v1/im/ws")
            )
          )
        } yield response
      }
    case req @ PUT -> Root / "api" / "v1" / "im" / "devices" / device / "push-token" =>
      authorized(req, "user") { who =>
        for {
          data <- body(req); _ <- Async[F].delay(Protocol.bounded(device, "device_id"));
          token <- Async[F].delay {
            data.hcursor.get[String]("token").toOption.filter(_.nonEmpty).map { value =>
              if (!value.matches("[a-fA-F0-9]{32,512}"))
                throw ApiError(422, "INVALID_ARGUMENT", "invalid APNs device token")
              value.toLowerCase
            }
          }
          _ <- store.patchDevice(
            who,
            device,
            token,
            data.hcursor.get[Boolean]("muted").toOption,
            data.hcursor.get[Boolean]("preview").toOption
          ); response <- json(200, Json.obj("ok" -> Json.True))
        } yield response
      }
    case req @ GET -> Root / "api" / "v1" / "im" / "devices" / device / "preferences" =>
      authorized(req, "user")(who => store.preferences(who, device).flatMap(p => json(200, p)))
    case req @ DELETE -> Root / "api" / "v1" / "im" / "devices" / device / "session" =>
      authorized(req, "user")(who => store.logout(who, device) *> json(200, Json.obj("ok" -> Json.True)))
    case req @ PUT -> Root / "api" / "v1" / "im" / "devices" / device / "ack" =>
      authorized(req, "user") { who =>
        for {
          data <- body(req); n <- Async[F].delay(cursors.decode(who, Some(Protocol.field(data, "cursor"))));
          _ <- store.ack(who, device, n); response <- json(200, Json.obj("ok" -> Json.True))
        } yield response
      }
    case req @ GET -> Root / "api" / "v1" / "im" / "ws" =>
      (for {
        token <- Async[F].fromOption(
          req.uri.query.params.get("ticket"),
          ApiError(401, "INVALID_TICKET", "ticket required")
        )
        identity <- store.consumeTicket(token); (who, device) = identity
        from     <- Async[F].delay(cursors.decode(who, req.uri.query.params.get("cursor")))
        sent     <- Ref.of[F, Long](from)
        signals = changes.fold(
          Resource.pure[F, Stream[F, Unit]](Stream.emit(()) ++ Stream.awakeEvery[F](5.seconds).void)
        )(_.subscribe(who))
        send = Stream
          .resource(signals)
          .flatMap { ticks =>
            ticks.flatMap { _ =>
              // 同一次唤醒立即追完所有分页；慢连接只保留一个待处理唤醒。
              Stream
                .repeatEval {
                  for {
                    current <- sent.get
                    page    <- sync(who, current, 25, Some(device))
                    next    <- Async[F].delay(cursors.decode(who, Some(Protocol.field(page, "cursor"))))
                    _       <- sent.set(next)
                  } yield page
                }
                .takeThrough(_.hcursor.get[Boolean]("has_more").getOrElse(false))
                .map(page => WebSocketFrame.Text(Json.obj("type" -> Json.fromString("sync"), "data" -> page).noSpaces))
            }
          }
          .interruptAfter((who.expires - Protocol.now).max(1).seconds)
        ping = Stream.awakeEvery[F](20.seconds).as(WebSocketFrame.Ping())
        receive: Pipe[F, WebSocketFrame, Unit] = _.evalMap {
          case WebSocketFrame.Text(text, _) if text.length <= 4096 =>
            Async[F]
              .delay {
                val data = parse(text).toOption.getOrElse(throw ApiError(400, "INVALID_ARGUMENT", "invalid frame"))
                cursors.decode(who, Some(Protocol.field(data, "cursor")))
              }
              .flatMap(n =>
                sent.get.flatMap(max =>
                  if (n <= max) store.ack(who, device, n)
                  else Async[F].raiseError(ApiError(422, "INVALID_ARGUMENT", "ack exceeds delivered position"))
                )
              )
          case _: WebSocketFrame.Pong  => Async[F].unit
          case _: WebSocketFrame.Close => Async[F].unit
          case _ => Async[F].raiseError(ApiError(400, "INVALID_ARGUMENT", "unsupported websocket frame"))
        }
        response <- ws.build(send.mergeHaltL(ping), receive)
      } yield response).handleErrorWith(fail)
  }
}
