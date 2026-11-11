package polyglot.im

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption, StandardCopyOption}
import java.net.{URI, URLEncoder}
import java.util.UUID
import scala.concurrent.duration.*
import cats.effect.{Async, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cats.effect.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.{parse, decode}
import polyglot.im.transport.*

final case class ImError(status: Int, code: String, message: String) extends RuntimeException(message)
final case class AccountScope(environment: String, appId: String, userId: String) {
  def key: String = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
    Json
      .arr(Json.fromString(environment.stripSuffix("/")), Json.fromString(appId), Json.fromString(userId))
      .noSpaces
      .getBytes(UTF_8)
  )
}
object LocalCache {
  val empty: Json = Json.obj(
    "messages"       -> Json.obj(),
    "cursor"         -> Json.Null,
    "event_position" -> Json.fromString("0"),
    "latest_seq"     -> Json.fromString("0"),
    "read_seq"       -> Json.fromString("0"),
    "unread_count"   -> Json.fromString("0")
  )
  def field(value: Json, name: String): String = value.hcursor.get[String](name).toOption.get
  def applyPage(current: Json, page: Json, reset: Boolean = false): Json = {
    val oldPosition = BigInt(field(current, "event_position"))
    if (page.hcursor.get[String]("event_position").toOption.exists(v => BigInt(v) < oldPosition)) current
    else {
      var result   = if (reset) empty else current
      var messages = result.hcursor.downField("messages").focus.get.asObject.get
      val added = page.hcursor.get[List[Json]]("messages").getOrElse(Nil) ++ page.hcursor
        .get[List[Json]]("events")
        .getOrElse(Nil)
        .filter(_.hcursor.get[String]("type").contains("message.created"))
        .map(_.hcursor.downField("data").focus.get)
      added.foreach { value =>
        val m =
          decode[Message](value.noSpaces).toOption.getOrElse(throw new IllegalArgumentException("invalid message"));
        messages = messages.add(m.messageId, value)
      }
      result = result.deepMerge(Json.obj("messages" -> Json.fromJsonObject(messages)))
      List("cursor", "event_position", "latest_seq", "read_seq", "unread_count", "conversation_id").foreach(k =>
        page.hcursor.downField(k).focus.foreach(v => result = result.deepMerge(Json.obj(k -> v)))
      )
      List("latest_seq", "read_seq").foreach { name =>
        if (!reset && BigInt(field(current, name)) > BigInt(field(result, name)))
          result = result.deepMerge(Json.obj(name -> Json.fromString(field(current, name))))
      }
      result.deepMerge(
        Json.obj(
          "unread_count" -> Json.fromString(
            (BigInt(field(result, "latest_seq")) - BigInt(field(result, "read_seq"))).toString
          )
        )
      )
    }
  }
}
trait LocalStore[F[_]] {
  def load(scope: AccountScope): F[Json]
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json]
  def clear(scope: AccountScope): F[Unit]
}

/** JVM 持久缓存适配器：跨进程文件锁 + 原子替换，失败不会确认游标。 */
final class AtomicFileStore[F[_]: Async](directory: Path) extends LocalStore[F] {
  private def transaction[A](scope: AccountScope)(run: Path => A): F[A] = Async[F].blocking {
    Files.createDirectories(directory)
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(scope.key.getBytes(UTF_8))
      .map(b => f"${b & 255}%02x")
      .mkString
    val channel =
      FileChannel.open(directory.resolve(digest + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      var lock: java.nio.channels.FileLock = null
      while (lock == null)
        try lock = channel.lock()
        catch { case _: java.nio.channels.OverlappingFileLockException => Thread.sleep(10) }
      try run(directory.resolve(digest + ".json"))
      finally lock.release()
    } finally channel.close()
  }
  private def read(path: Path): Json = if (Files.exists(path))
    parse(Files.readString(path)).toOption.getOrElse(throw new IllegalStateException("corrupt cache"))
  else LocalCache.empty
  def load(scope: AccountScope): F[Json] = transaction(scope)(read)
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json] = transaction(scope) { path =>
    val result = LocalCache.applyPage(read(path), page, reset)
    val temp   = path.resolveSibling(path.getFileName.toString + "." + UUID.randomUUID() + ".tmp")
    try {
      val file = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      try {
        val bytes = java.nio.ByteBuffer.wrap(result.noSpaces.getBytes(UTF_8));
        while (bytes.hasRemaining) file.write(bytes); file.force(true)
      } finally file.close()
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      result
    } finally Files.deleteIfExists(temp)
  }
  def clear(scope: AccountScope): F[Unit] = transaction(scope) { path =>
    Files.deleteIfExists(path); ()
  }
}
final class ApiClient[F[_]: Async](
    transport: HttpTransport[F],
    refresh: Option[F[Unit]] = None,
    expectedScope: Option[AccountScope] = None
) {
  def scoped(scope: AccountScope): ApiClient[F] = new ApiClient(transport, refresh, Some(scope))
  def request(method: String, path: String, body: Option[Json] = None): F[Json] = {
    def loop(attempt: Int, refreshed: Boolean): F[Json] =
      transport
        .execute(
          HttpRequest(
            method,
            "/api/v1/im" + path,
            headers = expectedScope.fold(Map.empty[String, String])(s =>
              Map(
                "X-IM-App-ID"  -> URLEncoder.encode(s.appId, UTF_8),
                "X-IM-User-ID" -> URLEncoder.encode(s.userId, UTF_8)
              )
            ),
            body = body
          )
        )
        .attempt
        .flatMap {
          case Left(error: java.io.IOException) if attempt < 3 =>
            Async[F].sleep((250L * (1L << attempt)).millis) *> loop(attempt + 1, refreshed)
          case Left(error) => Async[F].raiseError(error)
          case Right(response) =>
            (if (response.status >= 400) Async[F].pure(parse(response.body).getOrElse(Json.obj()))
             else Async[F].fromEither(parse(response.body))).flatMap { data =>
              if (response.status >= 200 && response.status < 300) Async[F].pure(data)
              else if (response.status == 401 && refresh.nonEmpty && !refreshed) refresh.get *> loop(attempt, true)
              else if (Set(429, 500, 502, 503, 504)(response.status) && attempt < 3) {
                val delay = response.headers.get("retry-after").flatMap(_.toLongOption).getOrElse(0L).min(30).max(0)
                Async[F].sleep((250L * (1L << attempt)).millis.max(delay.seconds)) *> loop(attempt + 1, refreshed)
              } else
                Async[F].raiseError(
                  ImError(
                    response.status,
                    data.hcursor.get[String]("code").getOrElse("HTTP_ERROR"),
                    data.hcursor.get[String]("message").getOrElse("request failed")
                  )
                )
            }
        }
    loop(0, false)
  }
}
final class NotificationSender[F[_]: Async](api: ApiClient[F]) {
  def send(recipientId: String, clientMessageId: String, kind: String, payload: Json): F[Message] =
    if (clientMessageId.isEmpty) Async[F].raiseError(new IllegalArgumentException("stable idempotency key required"))
    else
      api
        .request(
          "POST",
          "/notifications",
          Some(
            Json.obj(
              "recipient_id"      -> Json.fromString(recipientId),
              "client_message_id" -> Json.fromString(clientMessageId),
              "type"              -> Json.fromString(kind),
              "payload"           -> payload
            )
          )
        )
        .flatMap(j => Async[F].fromEither(decode[Message](j.noSpaces)))
}
trait SocketSession[F[_]]   { def messages: Stream[F, String]; def ack(cursor: String): F[Unit] }
trait SocketConnector[F[_]] { def connect(uri: URI): cats.effect.Resource[F, SocketSession[F]]  }
final class ImClient[F[_]: Async] private (
    api: ApiClient[F],
    scope: AccountScope,
    deviceId: String,
    store: LocalStore[F],
    socket: SocketConnector[F],
    cache: Ref[F, Json],
    gate: Semaphore[F],
    closed: Ref[F, Boolean]
) {
  private def open: F[Unit] =
    closed.get.flatMap(v => if (v) Async[F].raiseError(new IllegalStateException("client closed")) else Async[F].unit)
  private def query(value: String): String = URLEncoder.encode(value, UTF_8)
  def state: F[Json]                       = cache.get
  private def session: F[Json] =
    api.request("POST", "/session", Some(Json.obj("device_id" -> Json.fromString(deviceId)))).flatTap { data =>
      if (LocalCache.field(data, "app_id") == scope.appId && LocalCache.field(data, "user_id") == scope.userId) open
      else Async[F].raiseError(ImError(403, "IDENTITY_MISMATCH", "credential differs from cache scope"))
    }
  def initialize: F[Json] = open *> store.load(scope).flatTap(cache.set) *> session *> sync
  def sync: F[Json] = gate.permit.use { _ =>
    def loop: F[Json] = for {
      _ <- open; current <- cache.get
      result <- current.hcursor.get[String]("cursor").toOption match {
        case None => api.request("GET", "/snapshot").map(_ -> true)
        case Some(cursor) =>
          api.request("GET", "/sync?cursor=" + query(cursor)).map(_ -> false).handleErrorWith {
            case e: ImError if e.code == "RESET_REQUIRED" => api.request("GET", "/snapshot").map(_ -> true)
            case e                                        => Async[F].raiseError(e)
          }
      }
      _   <- open; updated <- store.commit(scope, result._1, result._2); _ <- open; _ <- cache.set(updated)
      out <- if (result._1.hcursor.get[Boolean]("has_more").getOrElse(false)) loop else Async[F].pure(updated)
    } yield out
    loop
  }
  def history(before: Option[String] = None, limit: Int = 50): F[Json] = for {
    _      <- open;
    result <- api.request("GET", s"/messages?limit=$limit" + before.fold("")(v => "&before_seq=" + query(v))); _ <- open
    updated <- store.commit(scope, Json.obj("messages" -> result.hcursor.downField("items").focus.get)); _ <- open;
    _       <- cache.set(updated)
  } yield result
  def getMessage(id: String): F[Message] =
    open *> api.request("GET", "/messages/" + query(id)).flatMap(j => Async[F].fromEither(decode[Message](j.noSpaces)))
  def markRead(seq: Option[String] = None): F[Json] = for {
    _ <- open; current <- cache.get; target = seq.getOrElse(LocalCache.field(current, "latest_seq"))
    _ <- api.request("PUT", "/read", Some(Json.obj("up_to_seq" -> Json.fromString(target)))); result <- sync
  } yield result
  def setPush(token: Option[String], muted: Boolean = false, preview: Boolean = false): F[Unit] = open *> api
    .request(
      "PUT",
      s"/devices/${query(deviceId)}/push-token",
      Some(
        Json.obj(
          "token"   -> token.fold(Json.Null)(Json.fromString),
          "muted"   -> Json.fromBoolean(muted),
          "preview" -> Json.fromBoolean(preview)
        )
      )
    )
    .void
  def watch: Stream[F, Json] = {
    def connection: Stream[F, Json] = Stream.eval(sync *> session).flatMap { info =>
      Stream.eval(cache.get).flatMap { current =>
        val base = URI.create(scope.environment.stripSuffix("/"))
        val uri = new URI(
          if (base.getScheme == "https") "wss" else "ws",
          base.getAuthority,
          base.getPath + "/api/v1/im/ws",
          "ticket=" + query(LocalCache.field(info, "ticket")) + "&cursor=" + query(LocalCache.field(current, "cursor")),
          null
        )
        Stream.resource(socket.connect(uri)).flatMap { ws =>
          ws.messages.evalMap { text =>
            for {
              page   <- Async[F].fromEither(parse(text)).map(_.hcursor.downField("data").focus.get)
              result <- gate.permit.use(_ => open *> store.commit(scope, page).flatTap(v => open *> cache.set(v)))
              _      <- ws.ack(LocalCache.field(page, "cursor"))
            } yield result
          }
        }
      }
    }
    def loop(delay: FiniteDuration): Stream[F, Json] = connection.handleErrorWith {
      case e: ImError if e.status == 401 || e.code == "IDENTITY_MISMATCH" => Stream.raiseError[F](e)
      case _ =>
        Stream
          .eval(closed.get)
          .flatMap(c => if (c) Stream.empty else Stream.sleep_[F](delay) ++ loop((delay * 2).min(30.seconds)))
    } ++ Stream.eval(closed.get).flatMap(c => if (c) Stream.empty else Stream.sleep_[F](250.millis) ++ loop(250.millis))
    loop(250.millis)
  }
  def close(logout: Boolean = true, clearCache: Boolean = false): F[Unit] = closed.getAndSet(true).flatMap { wasClosed =>
    if (wasClosed) Async[F].unit
    else
      (if (logout) api.request("DELETE", s"/devices/${query(deviceId)}/session").void else Async[F].unit)
        .guarantee(if (clearCache) store.clear(scope) else Async[F].unit)
  }
}
object ImClient {
  def create[F[_]: Async](
      api: ApiClient[F],
      scope: AccountScope,
      deviceId: String,
      store: LocalStore[F],
      socket: SocketConnector[F]
  ): F[ImClient[F]] = for {
    cache <- Ref.of[F, Json](LocalCache.empty); gate <- Semaphore[F](1); closed <- Ref.of[F, Boolean](false)
  } yield new ImClient(api.scoped(scope), scope, deviceId, store, socket, cache, gate, closed)
}
