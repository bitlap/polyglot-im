package polyglot.im.service

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*
import cats.effect.{Async, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cats.effect.syntax.all.*
import fs2.Stream
import io.circe.Json
import io.circe.parser.decode
import polyglot.im.domain.{AccountScope, LocalCache, Message, ImError}
import polyglot.im.ports.{RemoteApi, LocalStore, SocketConnector, WireCodec}

/** 装配账号作用域、同步存储和实时连接的客户端入口。 */
final class ImClient[F[_]: Async] private[im] (
    api: RemoteApi[F],
    scope: AccountScope,
    deviceId: String,
    store: LocalStore[F],
    socket: SocketConnector[F],
    cache: Ref[F, Json],
    gate: Semaphore[F],
    closed: Ref[F, Boolean],
    codec: WireCodec
) {
  private def open: F[Unit] =
    closed.get.flatMap(v => if (v) Async[F].raiseError(new IllegalStateException("client closed")) else Async[F].unit)
  private def query(value: String): String = URLEncoder.encode(value, UTF_8)

  /** 读取最近一次成功提交的账号缓存；未完成落盘的事件不会在此发布。 */
  def state: F[Json] = cache.get
  private def session: F[Json] =
    api.request("POST", "/session", Some(Json.obj("device_id" -> Json.fromString(deviceId)))).flatTap { data =>
      if (LocalCache.field(data, "app_id") == scope.appId && LocalCache.field(data, "user_id") == scope.userId) open
      else Async[F].raiseError(ImError(403, "IDENTITY_MISMATCH", "credential differs from cache scope"))
    }

  /** 加载当前账号缓存、建立设备会话并补齐可靠消息状态。 */
  def initialize: F[Json] = open *> store.load(scope).flatTap(cache.set) *> session *> sync

  /** 串行追赶增量；游标过期时取快照，缓存与游标提交成功后才发布新状态。 */
  def sync: F[Json] = gate.permit.use { _ =>
    /** 在当前操作范围内继续处理后续页或下一次连接尝试。 */
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

  /** 按 before_seq 向前分页读取通知历史；历史页不推进增量游标。 */
  def history(before: Option[String] = None, limit: Int = 50): F[Json] = for {
    _      <- open;
    result <- api.request("GET", s"/messages?limit=$limit" + before.fold("")(v => "&before_seq=" + query(v))); _ <- open
    updated <- store.commit(scope, Json.obj("messages" -> result.hcursor.downField("items").focus.get)); _ <- open;
    _       <- cache.set(updated)
  } yield result
  private def commitConversations(patches: List[Json]): F[Unit] = gate.permit.use { _ =>
    open *> store.commit(scope, Json.obj("conversations" -> Json.arr(patches*))).flatMap(v => open *> cache.set(v))
  }

  /** 按对方用户身份创建或取得唯一单聊会话。 */
  def createDirect(peerUserId: String): F[Json] = for {
    _ <- open
    result <- api.request(
      "POST",
      "/conversations/direct",
      Some(Json.obj("peer_user_id" -> Json.fromString(peerUserId)))
    )
    _ <- commitConversations(List(result))
  } yield result

  /** 从 after 位置分页取得当前用户可见的单聊会话摘要。 */
  def conversations(after: String = "", limit: Int = 50): F[Json] = for {
    _      <- open
    result <- api.request("GET", s"/conversations?limit=$limit&after=${query(after)}")
    _      <- commitConversations(result.hcursor.get[List[Json]]("items").toOption.get)
  } yield result

  /** 读取指定单聊的历史页并合并会话缓存，不推进账号同步游标。 */
  def conversationHistory(id: String, before: Option[String] = None, limit: Int = 50): F[Json] = for {
    _ <- open
    result <- api.request(
      "GET",
      s"/conversations/${query(id)}/messages?limit=$limit" + before.fold("")(v => "&before_seq=" + query(v))
    )
    _ <- commitConversations(List(result.hcursor.downField("conversation").focus.get))
  } yield result

  /** 发送文字或 Unicode 表情；重试必须保留 clientMessageId，成功后更新本地同步状态。 */
  def sendText(id: String, clientMessageId: String, text: String): F[Message] = for {
    _ <- open
    _ <- Async[F].delay(require(clientMessageId.nonEmpty, "stable clientMessageId required"))
    result <- api.request(
      "POST",
      s"/conversations/${query(id)}/messages",
      Some(
        Json.obj(
          "client_message_id" -> Json.fromString(clientMessageId),
          "type"              -> Json.fromString("chat.text"),
          "payload"           -> Json.obj("text" -> Json.fromString(text))
        )
      )
    )
    _       <- sync
    message <- Async[F].fromEither(decode[Message](result.noSpaces))
  } yield message

  /** 提交指定会话实际可见的阅读水位；水位只增，不替其他会话标记已读。 */
  def markConversationRead(id: String, upToSeq: String): F[Json] = for {
    _ <- open
    result <- api.request(
      "PUT",
      s"/conversations/${query(id)}/read",
      Some(Json.obj("up_to_seq" -> Json.fromString(upToSeq)))
    )
    _ <- commitConversations(List(result))
    _ <- sync
  } yield result

  /** 读取当前账号有权访问的一条消息；不可见与不存在使用相同错误边界。 */
  def getMessage(id: String): F[Message] =
    open *> api.request("GET", "/messages/" + query(id)).flatMap(j => Async[F].fromEither(decode[Message](j.noSpaces)))

  /** 标记通知阅读位置；省略序号时捕获当前已知高水位，不误清之后的新消息。 */
  def markRead(seq: Option[String] = None): F[Json] = for {
    _ <- open; current <- cache.get; target = seq.getOrElse(LocalCache.field(current, "latest_seq"))
    _ <- api.request("PUT", "/read", Some(Json.obj("up_to_seq" -> Json.fromString(target)))); result <- sync
  } yield result

  /** 更新设备 token、静音和锁屏正文偏好；token 为空表示解绑。 */
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

  /** 持续同步实时事件并在断线后重连；消费前先完成本地消息与游标提交。 */
  def watch: Stream[F, Json] = {

    /** 先补拉并取得新会话票据，再建立实时连接。 */
    def connection: Stream[F, Json] = Stream.eval(sync *> session).flatMap { info =>
      Stream.eval(cache.get).flatMap { current =>
        val base = URI.create(scope.environment.stripSuffix("/"))
        val uri = new URI(
          if (base.getScheme == "https") "wss" else "ws",
          base.getAuthority,
          base.getPath + polyglot.im.config.ProtocolConfig.apiPrefix + "/ws",
          "ticket=" + query(LocalCache.field(info, "ticket")) + "&cursor=" + query(LocalCache.field(current, "cursor")),
          null
        )
        Stream.resource(socket.connect(uri)).flatMap { ws =>
          ws.messages.evalMap { text =>
            for {
              page   <- Async[F].fromEither(codec.decode(text)).map(_.hcursor.downField("data").focus.get)
              result <- gate.permit.use(_ => open *> store.commit(scope, page).flatTap(v => open *> cache.set(v)))
              _      <- ws.ack(LocalCache.field(page, "cursor"))
            } yield result
          }
        }
      }
    }

    /** 在当前操作范围内继续处理后续页或下一次连接尝试。 */
    def loop(delay: FiniteDuration): Stream[F, Json] = connection.handleErrorWith {
      case e: ImError if e.status == 401 || e.code == "IDENTITY_MISMATCH" => Stream.raiseError[F](e)
      case _ =>
        Stream
          .eval(closed.get)
          .flatMap(c => if (c) Stream.empty else Stream.sleep_[F](delay) ++ loop((delay * 2).min(30.seconds)))
    } ++ Stream.eval(closed.get).flatMap(c => if (c) Stream.empty else Stream.sleep_[F](250.millis) ++ loop(250.millis))
    loop(250.millis)
  }

  /** 终止当前客户端；按参数注销设备或清理当前作用域缓存，不再接收迟到更新。 */
  def close(logout: Boolean = true, clearCache: Boolean = false): F[Unit] = closed.getAndSet(true).flatMap { wasClosed =>
    if (wasClosed) Async[F].unit
    else
      (if (logout) api.request("DELETE", s"/devices/${query(deviceId)}/session").void else Async[F].unit)
        .guarantee(if (clearCache) store.clear(scope) else Async[F].unit)
  }
}
