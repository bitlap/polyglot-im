package polyglot.im.server

import java.sql.{Connection, ResultSet}
import javax.sql.DataSource
import cats.effect.{Async, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.Json
import io.circe.parser.parse

/** 所有可靠状态位于 PostgreSQL；每用户行锁保证游标按事务提交顺序可见。 */
final class Store[F[_]: Async](source: DataSource) {
  private def transaction[A](f: Connection => A): F[A] = transact(false)(f)
  private def reading[A](f: Connection => A): F[A]     = transact(true)(f)
  private def transact[A](readOnly: Boolean)(f: Connection => A): F[A] = Async[F].blocking {
    val c = source.getConnection
    try {
      c.setReadOnly(readOnly)
      c.setTransactionIsolation(
        if (readOnly) Connection.TRANSACTION_REPEATABLE_READ else Connection.TRANSACTION_READ_COMMITTED
      )
      c.setAutoCommit(false)
      try { val value = f(c); c.commit(); value }
      catch { case e: Throwable => c.rollback(); throw e }
    } finally c.close()
  }
  private def bind(statement: java.sql.PreparedStatement, values: Seq[Any]): Unit = values.zipWithIndex.foreach {
    case (v, i) => statement.setObject(i + 1, v)
  }
  private def update(c: Connection, sql: String, values: Any*): Int = {
    val s = c.prepareStatement(sql);
    try { bind(s, values); s.executeUpdate() }
    finally s.close()
  }
  private def query[A](c: Connection, sql: String, values: Seq[Any])(read: ResultSet => A): List[A] = {
    val s = c.prepareStatement(sql)
    try {
      bind(s, values); val rs = s.executeQuery();
      try { val out = List.newBuilder[A]; while (rs.next()) out += read(rs); out.result() }
      finally rs.close()
    } finally s.close()
  }
  private def state(c: Connection, who: Identity, lock: Boolean = false): Position = {
    if (lock)
      update(c, "INSERT INTO im_user_state(app_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING", who.app, who.user)
    query(
      c,
      "SELECT cursor,message_seq,read_seq,retained_from,direct_unread FROM im_user_state WHERE app_id=? AND user_id=?" + (if (
                                                                                                                            lock
                                                                                                                          )
                                                                                                                            " FOR UPDATE"
                                                                                                                          else
                                                                                                                            ""),
      Seq(who.app, who.user)
    )(r => Position(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5))).headOption
      .getOrElse(Position(0, 0, 0, 0))
  }
  private def databaseNow(c: Connection): Long =
    query(c, "SELECT floor(extract(epoch FROM clock_timestamp()))::bigint", Nil)(_.getLong(1)).head
  private def notifyChange(c: Connection, who: Identity): Unit = {
    query(c, "SELECT pg_notify('polyglot_im_changes', ?)", Seq(ChangeFeed.key(who)))(_ => ()); ()
  }
  private def message(r: ResultSet): StoredMessage = StoredMessage(
    r.getString("message_id"),
    r.getString("app_id"),
    r.getString("user_id"),
    r.getLong("seq"),
    r.getString("type"),
    parse(r.getString("payload")).toOption.get,
    r.getString("created_at")
  )
  private def event(c: Connection, who: Identity, n: Long, kind: String, data: Json): Unit = {
    update(
      c,
      "INSERT INTO im_event(app_id,user_id,cursor,event_id,type,data,created_at) VALUES(?,?,?,?,?,?,?)",
      who.app,
      who.user,
      n,
      Protocol.id,
      kind,
      data.noSpaces,
      Protocol.time
    )
    update(c, "UPDATE im_user_state SET cursor=? WHERE app_id=? AND user_id=?", n, who.app, who.user)
    notifyChange(c, who)
  }
  def migrate: F[Unit] = transaction { c =>
    val statement = c.createStatement()
    try {
      statement.execute("SELECT pg_advisory_xact_lock(674239011)")
      statement.execute("CREATE TABLE IF NOT EXISTS im_schema_version(version INTEGER PRIMARY KEY)")
      val applied = query(c, "SELECT version FROM im_schema_version", Nil)(_.getInt(1)).toSet
      List(1, 2, 3).filterNot(applied).foreach { version =>
        val sql = scala.util.Using.resource(scala.io.Source.fromResource(s"db/V$version.sql"))(_.mkString)
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      }
    } finally statement.close()
  }
  def ready: F[Boolean] =
    reading(c =>
      query(c, "SELECT version FROM im_schema_version WHERE version=3 AND NOT pg_is_in_recovery()", Nil)(
        _.getInt(1)
      ).nonEmpty
    )
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] = transaction { c =>
    // 幂等键跨接收者生效；相同键但不同目标/内容不能创建第二条消息。
    query(
      c,
      "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
      Seq(
        Protocol.canonical(
          Json.arr(Json.fromString(sender.app), Json.fromString(sender.user), Json.fromString(command.key))
        )
      )
    )(_ => ())
    val fingerprint =
      Protocol.digest(command.recipient + "\n" + command.kind + "\n" + Protocol.canonical(command.payload))
    val existing = query(
      c,
      "SELECT * FROM im_message WHERE app_id=? AND sender_id=? AND client_key=?",
      Seq(sender.app, sender.user, command.key)
    )(r => (message(r), r.getString("fingerprint")))
    existing.headOption match {
      case Some((m, fp)) =>
        if (fp != fingerprint)
          throw ApiError(409, "IDEMPOTENCY_CONFLICT", "idempotency key already used with different content")
        (m, false)
      case None =>
        val recipient = sender.copy(user = command.recipient, role = "user")
        val current   = state(c, recipient, true)
        val m = StoredMessage(
          Protocol.id,
          sender.app,
          command.recipient,
          current.seq + 1,
          command.kind,
          command.payload,
          Protocol.time
        )
        update(
          c,
          "INSERT INTO im_message(message_id,app_id,user_id,seq,sender_id,client_key,fingerprint,type,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
          m.id,
          m.app,
          m.user,
          m.seq,
          sender.user,
          command.key,
          fingerprint,
          m.kind,
          m.payload.noSpaces,
          m.created
        )
        update(c, "UPDATE im_user_state SET message_seq=? WHERE app_id=? AND user_id=?", m.seq, m.app, m.user)
        event(c, recipient, current.cursor + 1, "message.created", m.json)
        val devices = query(
          c,
          "SELECT device_id FROM im_device WHERE app_id=? AND user_id=? AND active=TRUE AND token IS NOT NULL",
          Seq(m.app, m.user)
        )(_.getString(1))
        val pushAt = databaseNow(c) + 3
        devices.foreach(device =>
          update(
            c,
            "INSERT INTO im_outbox(id,app_id,user_id,message_id,device_id,next_at) VALUES(?,?,?,?,?,?)",
            Protocol.id,
            m.app,
            m.user,
            m.id,
            device,
            pushAt
          )
        )
        (m, true)
    }
  }

  final private case class Direct(
      id: String,
      low: String,
      high: String,
      seq: Long,
      revision: Long,
      lowRead: Long,
      highRead: Long,
      lowUnread: Long,
      highUnread: Long
  ) {
    def users: List[String]      = List(low, high)
    def read(user: String): Long = if (user == low) lowRead else highRead
  }
  private def direct(c: Connection, who: Identity, id: String, lock: Boolean = false): Direct =
    query(
      c,
      "SELECT * FROM im_direct_conversation WHERE app_id=? AND conversation_id=? AND (user_low=? OR user_high=?)" + (if (
                                                                                                                       lock
                                                                                                                     ) " FOR UPDATE"
                                                                                                                     else
                                                                                                                       ""),
      Seq(who.app, id, who.user, who.user)
    ) { r =>
      Direct(
        r.getString("conversation_id"),
        r.getString("user_low"),
        r.getString("user_high"),
        r.getLong("message_seq"),
        r.getLong("revision"),
        r.getLong("low_read"),
        r.getLong("high_read"),
        r.getLong("low_unread"),
        r.getLong("high_unread")
      )
    }.headOption.getOrElse(throw ApiError(404, "NOT_FOUND", "conversation not found"))
  private def directMessage(r: ResultSet, user: String): StoredMessage = StoredMessage(
    r.getString("message_id"),
    r.getString("app_id"),
    user,
    r.getLong("seq"),
    r.getString("type"),
    parse(r.getString("payload")).toOption.get,
    r.getString("created_at"),
    Some(r.getString("conversation_id"))
  )
  private def directJson(c: Connection, who: Identity, d: Direct, messages: List[StoredMessage] = Nil): Json = {
    val unread = if (who.user == d.low) d.lowUnread else d.highUnread
    Json.obj(
      "conversation_id" -> Json.fromString(d.id),
      "kind"            -> Json.fromString("direct"),
      "member_ids"      -> Json.arr(d.users.map(Json.fromString)*),
      "revision"        -> Json.fromString(d.revision.toString),
      "latest_seq"      -> Json.fromString(d.seq.toString),
      "read_seq"        -> Json.fromString(d.read(who.user).toString),
      "peer_read_seq"   -> Json.fromString(d.read(d.users.find(_ != who.user).get).toString),
      "unread_count"    -> Json.fromString(unread.toString),
      "messages"        -> Json.arr(messages.map(_.json)*)
    )
  }
  // Every direct write locks the conversation first, then BOTH account rows in the same order.
  // No notification/control transaction acquires conversation locks after account locks.
  private def directEvents(
      c: Connection,
      who: Identity,
      d: Direct,
      messages: List[StoredMessage] = Nil,
      unreadDelta: Map[String, Long] = Map.empty
  ): Unit = {
    val locked = d.users.map { user =>
      val member = who.copy(user = user); (member, state(c, member, true))
    }
    locked.foreach { case (member, pos) =>
      unreadDelta.get(member.user).filter(_ != 0).foreach { delta =>
        update(
          c,
          "UPDATE im_user_state SET direct_unread=direct_unread+? WHERE app_id=? AND user_id=?",
          delta,
          member.app,
          member.user
        )
      }
      event(c, member, pos.cursor + 1, "conversation.updated", directJson(c, member, d, messages))
    }
  }
  def createDirect(who: Identity, peer: String): F[Json] = transaction { c =>
    Protocol.bounded(peer, "peer_user_id")
    if (peer == who.user) throw ApiError(422, "INVALID_ARGUMENT", "direct conversation requires another user")
    val users = List(who.user, peer).sorted
    val id    = "direct_" + Protocol.digest(Protocol.canonical(Json.arr((who.app :: users).map(Json.fromString)*)))
    val created = update(
      c,
      "INSERT INTO im_direct_conversation(app_id,conversation_id,user_low,user_high) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
      who.app,
      id,
      users.head,
      users.last
    ) > 0
    val d = direct(c, who, id, true)
    if (created) directEvents(c, who, d)
    directJson(c, who, d)
  }
  private def directList(c: Connection, who: Identity, after: String, limit: Int): (List[Json], Boolean) = {
    val ids = query(
      c,
      "SELECT conversation_id FROM im_direct_conversation WHERE app_id=? AND (user_low=? OR user_high=?) AND conversation_id>? ORDER BY conversation_id LIMIT ?",
      Seq(who.app, who.user, who.user, after, limit + 1)
    )(_.getString(1))
    (ids.take(limit).map(id => directJson(c, who, direct(c, who, id))), ids.size > limit)
  }
  def conversations(who: Identity, after: String, limit: Int): F[Json] = reading { c =>
    val (items, more) = directList(c, who, after, limit)
    Json.obj(
      "items"      -> Json.arr(items*),
      "has_more"   -> Json.fromBoolean(more),
      "next_after" -> items.lastOption.fold(Json.Null)(_.hcursor.downField("conversation_id").focus.get)
    )
  }
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)] = reading { c =>
    val pos = state(c, who)
    val rows = query(
      c,
      "SELECT * FROM im_message WHERE app_id=? AND user_id=? ORDER BY seq DESC LIMIT 100",
      Seq(who.app, who.user)
    )(message)
    val (chats, more) = directList(c, who, "", 100)
    (pos, rows, chats, more)
  }
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json] = reading { c =>
    val d = direct(c, who, id)
    val rows = query(
      c,
      "SELECT * FROM im_direct_message WHERE app_id=? AND conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
      Seq(who.app, id, before, limit + 1)
    )(r => directMessage(r, who.user))
    Json.obj(
      "conversation"    -> directJson(c, who, d, rows.take(limit)),
      "items"           -> Json.arr(rows.take(limit).map(_.json)*),
      "has_more"        -> Json.fromBoolean(rows.size > limit),
      "next_before_seq" -> rows.take(limit).lastOption.fold(Json.Null)(m => Json.fromString(m.seq.toString))
    )
  }
  def sendDirect(who: Identity, id: String, data: Json): F[(StoredMessage, Boolean)] = transaction { c =>
    val key  = Protocol.bounded(Protocol.field(data, "client_message_id"), "client_message_id")
    val kind = Protocol.field(data, "type")
    if (kind != "chat.text") throw ApiError(422, "UNSUPPORTED_MESSAGE_TYPE", "only chat.text is supported")
    val text = Protocol.field(data.hcursor.downField("payload").focus.getOrElse(Json.Null), "text")
    if (text.trim.isEmpty || text.codePointCount(0, text.length) > 2048 || text.indexOf(0.toChar) >= 0)
      throw ApiError(422, "INVALID_ARGUMENT", "text must contain 1..2048 characters")
    // Sender and payload are server-authored; caller-supplied identity/attachments are rejected.
    val payloadInput = data.hcursor.downField("payload").focus.get
    if (payloadInput.asObject.forall(_.keys.toSet != Set("text")))
      throw ApiError(422, "INVALID_ARGUMENT", "only text payload is supported")
    val payload = Json.obj("text" -> Json.fromString(text), "sender_id" -> Json.fromString(who.user))
    val fp      = Protocol.digest(id + "\n" + Protocol.canonical(payload))
    query(
      c,
      "SELECT pg_advisory_xact_lock(hashtextextended(?,2))",
      Seq(Protocol.canonical(Json.arr(List(who.app, who.user, key).map(Json.fromString)*)))
    )(_ => ())
    val d = direct(c, who, id, true)
    val existing = query(
      c,
      "SELECT * FROM im_direct_message WHERE app_id=? AND sender_id=? AND client_key=?",
      Seq(who.app, who.user, key)
    )(r => (directMessage(r, who.user), r.getString("fingerprint")))
    existing.headOption match {
      case Some((m, fingerprint)) =>
        if (fingerprint != fp) throw ApiError(409, "IDEMPOTENCY_CONFLICT", "idempotency key already used")
        (m, false)
      case None =>
        val m = StoredMessage(Protocol.id, who.app, who.user, d.seq + 1, kind, payload, Protocol.time, Some(id))
        update(
          c,
          "INSERT INTO im_direct_message(message_id,app_id,conversation_id,seq,sender_id,client_key,fingerprint,type,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
          m.id,
          who.app,
          id,
          m.seq,
          who.user,
          key,
          fp,
          kind,
          payload.noSpaces,
          m.created
        )
        val peer         = d.users.find(_ != who.user).get
        val unreadColumn = if (peer == d.low) "low_unread" else "high_unread"
        update(
          c,
          s"UPDATE im_direct_conversation SET message_seq=?,revision=revision+1,$unreadColumn=$unreadColumn+1 WHERE app_id=? AND conversation_id=?",
          m.seq,
          who.app,
          id
        )
        directEvents(c, who, direct(c, who, id), List(m), Map(peer -> 1L))
        val pushAt = databaseNow(c) + 3
        query(
          c,
          "SELECT device_id FROM im_device WHERE app_id=? AND user_id=? AND active=TRUE AND token IS NOT NULL",
          Seq(who.app, peer)
        )(_.getString(1)).foreach { device =>
          update(
            c,
            "INSERT INTO im_outbox(id,app_id,user_id,message_id,device_id,next_at) VALUES(?,?,?,?,?,?)",
            Protocol.id,
            who.app,
            peer,
            m.id,
            device,
            pushAt
          )
        }
        (m, true)
    }
  }
  def readDirect(who: Identity, id: String, seq: Long): F[Json] = transaction { c =>
    val d = direct(c, who, id, true)
    if (seq > d.seq) throw ApiError(422, "INVALID_ARGUMENT", "read position exceeds current messages")
    if (seq > d.read(who.user)) {
      val column       = if (who.user == d.low) "low_read" else "high_read"
      val unreadColumn = if (who.user == d.low) "low_unread" else "high_unread"
      val cleared = query(
        c,
        "SELECT count(*) FROM im_direct_message WHERE app_id=? AND conversation_id=? AND seq>? AND seq<=? AND sender_id<>?",
        Seq(who.app, id, d.read(who.user), seq, who.user)
      )(_.getLong(1)).head
      update(
        c,
        s"UPDATE im_direct_conversation SET $column=?,revision=revision+1,$unreadColumn=$unreadColumn-? WHERE app_id=? AND conversation_id=?",
        seq,
        cleared,
        who.app,
        id
      )
      directEvents(c, who, direct(c, who, id), unreadDelta = Map(who.user -> -cleared))
    }
    directJson(c, who, direct(c, who, id))
  }

  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)] = reading { c =>
    val rows = query(
      c,
      "SELECT * FROM im_message WHERE app_id=? AND user_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
      Seq(who.app, who.user, before, limit + 1)
    )(message)
    (rows.take(limit), rows.size > limit)
  }
  def get(who: Identity, id: String): F[StoredMessage] = reading(c =>
    query(c, "SELECT * FROM im_message WHERE app_id=? AND user_id=? AND message_id=?", Seq(who.app, who.user, id))(
      message
    ).headOption
      .orElse(
        query(
          c,
          "SELECT m.* FROM im_direct_message m JOIN im_direct_conversation d USING(app_id,conversation_id) WHERE m.app_id=? AND m.message_id=? AND (d.user_low=? OR d.user_high=?)",
          Seq(who.app, id, who.user, who.user)
        )(r => directMessage(r, who.user)).headOption
      )
      .getOrElse(throw ApiError(404, "NOT_FOUND", "message not found"))
  )
  def position(who: Identity): F[Position] = reading(c => state(c, who))
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = reading { c =>
    val pos = state(c, who)
    (
      pos,
      query(
        c,
        "SELECT * FROM im_message WHERE app_id=? AND user_id=? ORDER BY seq DESC LIMIT 100",
        Seq(who.app, who.user)
      )(message)
    )
  }
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    reading(c => syncAt(c, who, from, limit))
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    reading { c =>
      if (!deviceActive(c, who, device)) throw ApiError(401, "UNAUTHORIZED", "device session revoked")
      syncAt(c, who, from, limit)
    }
  private def syncAt(c: Connection, who: Identity, from: Long, limit: Int): (Position, List[(Long, Json)], Boolean) = {
    val pos = state(c, who)
    if (from < pos.retained) throw ApiError(410, "RESET_REQUIRED", "cursor expired; obtain a new snapshot")
    if (from > pos.cursor) throw ApiError(400, "INVALID_CURSOR", "cursor is ahead of server")
    val events =
      if (from == pos.cursor) Nil
      else
        query(
          c,
          "SELECT cursor,event_id,type,data,created_at FROM im_event WHERE app_id=? AND user_id=? AND cursor>? ORDER BY cursor LIMIT ?",
          Seq(who.app, who.user, from, limit + 1)
        ) { r =>
          val n = r.getLong(1)
          (
            n,
            Json.obj(
              "event_id"    -> Json.fromString(r.getString(2)),
              "type"        -> Json.fromString(r.getString(3)),
              "data"        -> parse(r.getString(4)).toOption.get,
              "occurred_at" -> Json.fromString(r.getString(5))
            )
          )
        }
    (pos, events.take(limit), events.size > limit)
  }
  def read(who: Identity, seq: Long): F[Position] = transaction { c =>
    val pos = state(c, who, true)
    if (seq > pos.seq) throw ApiError(422, "INVALID_ARGUMENT", "read position exceeds current messages")
    if (seq > pos.read) {
      update(c, "UPDATE im_user_state SET read_seq=? WHERE app_id=? AND user_id=?", seq, who.app, who.user);
      event(c, who, pos.cursor + 1, "read.updated", Json.obj("read_seq" -> Json.fromString(seq.toString)))
    }
    state(c, who)
  }
  def register(who: Identity, device: String): F[Unit] = transaction { c =>
    state(c, who, true)
    update(
      c,
      "INSERT INTO im_device(app_id,user_id,device_id,updated_at) VALUES(?,?,?,?) ON CONFLICT(app_id,user_id,device_id) DO UPDATE SET active=TRUE,updated_at=EXCLUDED.updated_at",
      who.app,
      who.user,
      device,
      Protocol.now
    ); ()
  }
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit] =
    patchDevice(who, device, token, Some(muted), Some(preview))
  def patchDevice(
      who: Identity,
      device: String,
      token: Option[String],
      muted: Option[Boolean],
      preview: Option[Boolean]
  ): F[Unit] = transaction { c =>
    query(c, "SELECT pg_advisory_xact_lock(hashtextextended(?,1))", Seq(who.app))(_ => ())
    state(c, who, true)
    val existing = query(
      c,
      "SELECT active,muted,preview FROM im_device WHERE app_id=? AND user_id=? AND device_id=? FOR UPDATE",
      Seq(who.app, who.user, device)
    )(r => (r.getBoolean(1), r.getBoolean(2), r.getBoolean(3))).headOption
    if (!existing.exists(_._1))
      throw ApiError(409, "DEVICE_SESSION_CLOSED", "register an active device session before push token updates")
    val prefs = existing.get
    token.foreach(t => update(c, "UPDATE im_device SET token=NULL WHERE app_id=? AND token=?", who.app, t))
    update(
      c,
      "UPDATE im_device SET token=?,muted=?,preview=?,updated_at=? WHERE app_id=? AND user_id=? AND device_id=?",
      token.orNull,
      muted.getOrElse(prefs._2),
      preview.getOrElse(prefs._3),
      Protocol.now,
      who.app,
      who.user,
      device
    )
    ()
  }
  def preferences(who: Identity, device: String): F[Json] = reading { c =>
    query(
      c,
      "SELECT muted,preview FROM im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(r =>
      Json.obj("muted" -> Json.fromBoolean(r.getBoolean(1)), "preview" -> Json.fromBoolean(r.getBoolean(2)))
    ).headOption.getOrElse(Json.obj("muted" -> Json.False, "preview" -> Json.False))
  }
  def logout(who: Identity, device: String): F[Unit] = transaction { c =>
    state(c, who, true)
    update(
      c,
      "UPDATE im_device SET active=FALSE,token=NULL WHERE app_id=? AND user_id=? AND device_id=?",
      who.app,
      who.user,
      device
    ); update(c, "DELETE FROM im_ticket WHERE app_id=? AND user_id=? AND device_id=?", who.app, who.user, device)
    notifyChange(c, who); ()
  }
  def active(who: Identity, device: String): F[Boolean] = reading(c =>
    query(
      c,
      "SELECT active FROM im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(_.getBoolean(1)).headOption.contains(true)
  )
  def ack(who: Identity, device: String, value: Long): F[Unit] = transaction { c =>
    val pos = state(c, who); if (value > pos.cursor) throw ApiError(422, "INVALID_ARGUMENT", "invalid delivery cursor");
    update(
      c,
      "UPDATE im_device SET delivered_cursor=GREATEST(delivered_cursor,?) WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE AND delivered_cursor<?",
      value,
      who.app,
      who.user,
      device,
      value
    ); ()
  }
  def ticket(who: Identity, device: String): F[String] = transaction { c =>
    state(c, who, true)
    if (!deviceActive(c, who, device)) throw ApiError(401, "UNAUTHORIZED", "device session revoked")
    val now   = databaseNow(c)
    val token = Protocol.id + Protocol.id
    update(
      c,
      "INSERT INTO im_ticket(token_hash,app_id,user_id,device_id,expires_at,session_expires_at) VALUES(?,?,?,?,?,?)",
      Protocol.digest(token),
      who.app,
      who.user,
      device,
      (now + 30).min(who.expires),
      who.expires
    )
    token
  }
  private def deviceActive(c: Connection, who: Identity, device: String): Boolean =
    query(
      c,
      "SELECT active FROM im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(_.getBoolean(1)).headOption.contains(true)
  def consumeTicket(token: String): F[(Identity, String)] = transaction { c =>
    val hash = Protocol.digest(token)
    val candidate =
      query(c, "SELECT app_id,user_id,device_id,session_expires_at FROM im_ticket WHERE token_hash=?", Seq(hash))(r =>
        (Identity(r.getString(1), r.getString(2), "user", r.getLong(4)), r.getString(3))
      ).headOption
        .getOrElse(throw ApiError(401, "INVALID_TICKET", "invalid or expired websocket ticket"))
    val (who, device) = candidate
    state(c, who, true)
    val deleted = update(c, "DELETE FROM im_ticket WHERE token_hash=? AND expires_at>?", hash, databaseNow(c))
    if (deleted != 1 || !deviceActive(c, who, device))
      throw ApiError(401, "INVALID_TICKET", "invalid or expired websocket ticket")
    candidate
  }

  /** PostgreSQL 原子固定窗口限流；所有实例使用数据库时钟和同一个计数器。 */
  def allow(who: Identity, maximum: Int): F[Boolean] = transaction { c =>
    query(
      c,
      """INSERT INTO im_rate_limit(app_id,role,subject,window_start,hits)
      VALUES(?,?,?,floor(extract(epoch FROM clock_timestamp())/60)::bigint,1)
      ON CONFLICT(app_id,role,subject) DO UPDATE
      SET window_start=EXCLUDED.window_start,hits=CASE WHEN im_rate_limit.window_start=EXCLUDED.window_start THEN im_rate_limit.hits+1 ELSE 1 END
      WHERE im_rate_limit.window_start<>EXCLUDED.window_start OR im_rate_limit.hits<? RETURNING hits""",
      Seq(who.app, who.role, who.user, maximum)
    )(_.getInt(1)).nonEmpty
  }

  /** 只领取马上可执行的任务；claimToken 隔离过期 Worker 的回写。 */
  def claim: F[List[PushJob]] = claimBatch(25, 60)
  def claimBatch(batchSize: Int, leaseSeconds: Int): F[List[PushJob]] = transaction { c =>
    require(batchSize > 0 && batchSize <= 256 && leaseSeconds > 0)
    val now = databaseNow(c)
    update(
      c,
      "UPDATE im_outbox SET state='dead',lease_token=NULL,error='retry budget exhausted' WHERE state='pending' AND attempts>=10 AND lease_until<=?",
      now
    )
    val claimToken = Protocol.id
    query(
      c,
      """WITH selected AS (
      SELECT id FROM im_outbox WHERE state='pending' AND next_at<=? AND lease_until<=?
      ORDER BY next_at,id LIMIT ? FOR UPDATE SKIP LOCKED)
      UPDATE im_outbox j SET lease_until=?,lease_token=?,attempts=j.attempts+1
      FROM selected WHERE j.id=selected.id
      RETURNING j.id,j.app_id,j.user_id,j.message_id,j.device_id,j.attempts,j.lease_token""",
      Seq(now, now, batchSize, now + leaseSeconds, claimToken)
    )(r =>
      PushJob(
        r.getString(1),
        r.getString(2),
        r.getString(3),
        r.getString(4),
        r.getString(5),
        r.getInt(6),
        r.getString(7)
      )
    )
  }
  def pushTarget(job: PushJob): F[Option[PushTarget]] = transaction { c =>
    val who = Identity(job.app, job.user, "user", Long.MaxValue)
    val pos = state(c, who)
    val messages = query(
      c,
      "SELECT * FROM im_message WHERE message_id=? AND app_id=? AND user_id=?",
      Seq(job.message, job.app, job.user)
    )(message)
    val devices = query(
      c,
      "SELECT token,preview FROM im_device WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE AND muted=FALSE AND token IS NOT NULL",
      Seq(job.app, job.user, job.device)
    )(r => (r.getString(1), r.getBoolean(2)))
    val direct = query(
      c,
      "SELECT m.* FROM im_direct_message m JOIN im_direct_conversation d USING(app_id,conversation_id) WHERE m.app_id=? AND m.message_id=? AND ((d.user_low=? AND m.seq>d.low_read) OR (d.user_high=? AND m.seq>d.high_read)) AND m.sender_id<>?",
      Seq(who.app, job.message, who.user, who.user, who.user)
    )(r => directMessage(r, who.user)).headOption
    for { m <- messages.headOption.filter(_.seq > pos.read).orElse(direct); d <- devices.headOption } yield PushTarget(
      d._1,
      d._2,
      pos.unread + pos.directUnread,
      m
    )
  }
  def finish(job: PushJob, result: PushResult, token: Option[String]): F[Unit] = transaction { c =>
    val now = databaseNow(c)
    val owned = query(
      c,
      "SELECT id FROM im_outbox WHERE id=? AND lease_token=? AND lease_until>? AND state='pending' FOR UPDATE",
      Seq(job.id, job.claimToken, now)
    )(_.getString(1)).nonEmpty
    if (owned) {
      if (result.invalidToken)
        token.foreach(t =>
          update(
            c,
            "UPDATE im_device SET token=NULL WHERE app_id=? AND user_id=? AND device_id=? AND token=?",
            job.app,
            job.user,
            job.device,
            t
          )
        )
      val status = if (!result.retry) "done" else if (job.attempts >= 10) "dead" else "pending"
      update(
        c,
        "UPDATE im_outbox SET state=?,lease_until=0,lease_token=NULL,next_at=?,error=? WHERE id=? AND lease_token=?",
        status,
        now + (1L << job.attempts.min(10)).min(3600),
        result.reason.take(200),
        job.id,
        job.claimToken
      )
    }
    ()
  }

  /** 有界清理可丢弃的过期控制状态；消息、幂等记录和可靠 Outbox 不在此删除。 */
  def maintain: F[Unit] = transaction { c =>
    val elected = query(c, "SELECT pg_try_advisory_xact_lock(674239012)", Nil)(_.getBoolean(1)).head
    if (elected) {
      val now = databaseNow(c)
      update(
        c,
        "DELETE FROM im_ticket WHERE token_hash IN (SELECT token_hash FROM im_ticket WHERE expires_at<? LIMIT 1000)",
        now
      )
      update(
        c,
        "DELETE FROM im_rate_limit WHERE window_start < floor(extract(epoch FROM clock_timestamp())/60)::bigint - 2 AND (app_id,role,subject) IN (SELECT app_id,role,subject FROM im_rate_limit WHERE window_start<? LIMIT 1000)",
        now / 60 - 2
      )
    }
    ()
  }
  def purgeEvents(beforeEpochSeconds: Long): F[Unit] = transaction { c =>
    // 运维入口：逐用户锁定再清理，保留水位以明确触发 RESET_REQUIRED。
    val users = query(c, "SELECT app_id,user_id FROM im_user_state", Nil)(r => (r.getString(1), r.getString(2)))
    users.sortBy(identity).foreach { case (app, user) =>
      val who = Identity(app, user, "user", Long.MaxValue); state(c, who, true)
      val n = query(
        c,
        "SELECT COALESCE(MAX(cursor),0) FROM im_event WHERE app_id=? AND user_id=? AND created_at::timestamptz<to_timestamp(?)",
        Seq(app, user, beforeEpochSeconds)
      )(_.getLong(1)).head
      if (n > 0) {
        update(c, "DELETE FROM im_event WHERE app_id=? AND user_id=? AND cursor<=?", app, user, n);
        update(
          c,
          "UPDATE im_user_state SET retained_from=GREATEST(retained_from,?) WHERE app_id=? AND user_id=?",
          n,
          app,
          user
        )
      }
    }
  }
}
object Store {
  def resource[F[_]: Async](url: String, user: String, password: String): Resource[F, Store[F]] =
    resource(DatabaseConfig(url, user, password))
  def resource[F[_]: Async](config: DatabaseConfig): Resource[F, Store[F]] = Resource
    .make(Async[F].blocking {
      val cfg = new HikariConfig(); cfg.setJdbcUrl(config.url); cfg.setUsername(config.user);
      cfg.setPassword(config.password);
      cfg.setMaximumPoolSize(config.maxPoolSize); cfg.setConnectionTimeout(config.connectionTimeoutMs);
      cfg.setAutoCommit(false)
      cfg.setIsolateInternalQueries(true)
      cfg.setConnectionInitSql(
        s"SET statement_timeout='${config.statementTimeoutMs}ms'; SET lock_timeout='${config.lockTimeoutMs}ms'"
      )
      cfg.addDataSourceProperty("connectTimeout", "10")
      cfg.addDataSourceProperty("socketTimeout", "30")
      cfg.addDataSourceProperty("tcpKeepAlive", "true")
      new HikariDataSource(cfg)
    })(s => Async[F].blocking(s.close()))
    .map(new Store[F](_))
}
