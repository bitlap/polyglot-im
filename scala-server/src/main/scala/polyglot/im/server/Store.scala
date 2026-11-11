package polyglot.im.server

import java.sql.{Connection, ResultSet}
import javax.sql.DataSource
import cats.effect.{Async, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.Json
import io.circe.parser.parse

/** 所有可靠状态位于 PostgreSQL；每用户行锁保证游标按事务提交顺序可见。 */
final class Store[F[_]: Async](source: DataSource) {
  private def transaction[A](f: Connection => A): F[A] = Async[F].blocking {
    val c = source.getConnection
    try {
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
    update(c, "INSERT INTO im_user_state(app_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING", who.app, who.user)
    query(
      c,
      "SELECT cursor,message_seq,read_seq,retained_from FROM im_user_state WHERE app_id=? AND user_id=?" + (if (lock)
                                                                                                              " FOR UPDATE"
                                                                                                            else ""),
      Seq(who.app, who.user)
    )(r => Position(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4))).head
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
  }
  def migrate: F[Unit] = transaction { c =>
    val sql       = scala.util.Using.resource(scala.io.Source.fromResource("db/V1.sql"))(_.mkString)
    val statement = c.createStatement();
    try {
      statement.execute("SELECT pg_advisory_xact_lock(674239011)");
      sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
    } finally statement.close()
  }
  def ready: F[Boolean] =
    transaction(c => query(c, "SELECT version FROM im_schema_version WHERE version=1", Nil)(_.getInt(1)).nonEmpty)
  def send(sender: Identity, command: Notification): F[(StoredMessage, Boolean)] = transaction { c =>
    // 幂等键跨接收者生效；相同键但不同目标/内容不能创建第二条消息。
    query(
      c,
      "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
      Seq(sender.app + ":" + sender.user + ":" + command.key)
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
        devices.foreach(device =>
          update(
            c,
            "INSERT INTO im_outbox(id,app_id,user_id,message_id,device_id,next_at) VALUES(?,?,?,?,?,?)",
            Protocol.id,
            m.app,
            m.user,
            m.id,
            device,
            Protocol.now + 3
          )
        )
        (m, true)
    }
  }
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean)] = transaction { c =>
    val rows = query(
      c,
      "SELECT * FROM im_message WHERE app_id=? AND user_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
      Seq(who.app, who.user, before, limit + 1)
    )(message)
    (rows.take(limit), rows.size > limit)
  }
  def get(who: Identity, id: String): F[StoredMessage] = transaction(c =>
    query(c, "SELECT * FROM im_message WHERE app_id=? AND user_id=? AND message_id=?", Seq(who.app, who.user, id))(
      message
    ).headOption.getOrElse(throw ApiError(404, "NOT_FOUND", "message not found"))
  )
  def position(who: Identity): F[Position] = transaction(c => state(c, who))
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = transaction { c =>
    val pos = state(c, who, true)
    (
      pos,
      query(
        c,
        "SELECT * FROM im_message WHERE app_id=? AND user_id=? ORDER BY seq DESC LIMIT 100",
        Seq(who.app, who.user)
      )(message)
    )
  }
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] = transaction { c =>
    val pos = state(c, who, true)
    if (from < pos.retained) throw ApiError(410, "RESET_REQUIRED", "cursor expired; obtain a new snapshot")
    if (from > pos.cursor) throw ApiError(400, "INVALID_CURSOR", "cursor is ahead of server")
    val events = query(
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
  def preferences(who: Identity, device: String): F[Json] = transaction { c =>
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
    ); update(c, "DELETE FROM im_ticket WHERE app_id=? AND user_id=? AND device_id=?", who.app, who.user, device); ()
  }
  def active(who: Identity, device: String): F[Boolean] = transaction(c =>
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
      "UPDATE im_device SET delivered_cursor=GREATEST(delivered_cursor,?) WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE",
      value,
      who.app,
      who.user,
      device
    ); ()
  }
  def ticket(who: Identity, device: String): F[String] = transaction { c =>
    update(c, "DELETE FROM im_ticket WHERE expires_at<?", Protocol.now)
    val token = Protocol.id + Protocol.id
    update(
      c,
      "INSERT INTO im_ticket(token_hash,app_id,user_id,device_id,expires_at,session_expires_at) VALUES(?,?,?,?,?,?)",
      Protocol.digest(token),
      who.app,
      who.user,
      device,
      (Protocol.now + 30).min(who.expires),
      who.expires
    )
    token
  }
  def consumeTicket(token: String): F[(Identity, String)] = transaction { c =>
    query(
      c,
      "DELETE FROM im_ticket WHERE token_hash=? AND expires_at>? RETURNING app_id,user_id,device_id,session_expires_at",
      Seq(Protocol.digest(token), Protocol.now)
    )(r => (Identity(r.getString(1), r.getString(2), "user", r.getLong(4)), r.getString(3))).headOption
      .getOrElse(throw ApiError(401, "INVALID_TICKET", "invalid or expired websocket ticket"))
  }
  def claim: F[List[PushJob]] = transaction { c =>
    val jobs = query(
      c,
      "SELECT id,app_id,user_id,message_id,device_id,attempts FROM im_outbox WHERE state='pending' AND next_at<=? AND lease_until<? ORDER BY next_at LIMIT 25 FOR UPDATE SKIP LOCKED",
      Seq(Protocol.now, Protocol.now)
    )(r => PushJob(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6)))
    jobs.foreach(j =>
      update(c, "UPDATE im_outbox SET lease_until=?,attempts=attempts+1 WHERE id=?", Protocol.now + 60, j.id)
    ); jobs
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
    for { m <- messages.headOption if m.seq > pos.read; d <- devices.headOption } yield PushTarget(
      d._1,
      d._2,
      pos.unread,
      m
    )
  }
  def finish(job: PushJob, result: PushResult, token: Option[String]): F[Unit] = transaction { c =>
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
    val status = if (!result.retry) "done" else if (job.attempts >= 9) "dead" else "pending"
    update(
      c,
      "UPDATE im_outbox SET state=?,lease_until=0,next_at=?,error=? WHERE id=?",
      status,
      Protocol.now + (1L << job.attempts.min(10)).min(3600),
      result.reason.take(200),
      job.id
    ); ()
  }
  def purgeEvents(beforeEpochSeconds: Long): F[Unit] = transaction { c =>
    // 运维入口：逐用户锁定再清理，保留水位以明确触发 RESET_REQUIRED。
    val users = query(c, "SELECT app_id,user_id FROM im_user_state", Nil)(r => (r.getString(1), r.getString(2)))
    users.foreach { case (app, user) =>
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
      new HikariDataSource(cfg)
    })(s => Async[F].blocking(s.close()))
    .map(new Store[F](_))
}
