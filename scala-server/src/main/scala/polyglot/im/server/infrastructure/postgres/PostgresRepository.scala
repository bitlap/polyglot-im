package polyglot.im.server.infrastructure.postgres

import polyglot.im.server.domain.ApiError.{Code, Message}
import polyglot.im.server.domain.RoutingKey
import polyglot.im.server.config.{DatabaseSettings, DirectChatSettings}
import polyglot.im.server.domain.PeerRelationship
import polyglot.im.server.domain.ApiError
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Notification
import polyglot.im.server.domain.Position
import polyglot.im.server.domain.Protocol
import polyglot.im.server.domain.PushJob
import polyglot.im.server.domain.PushResult
import polyglot.im.server.domain.PushTarget
import polyglot.im.server.domain.StoredMessage
import polyglot.im.server.ports.ImRepository

import java.sql.{Connection, ResultSet}
import javax.sql.DataSource
import cats.effect.{Async, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.Json
import io.circe.parser.parse

/** 所有可靠状态位于 PostgreSQL；每用户行锁保证游标按事务提交顺序可见。 */
final class PostgresRepository[F[_]: Async](
    source: DataSource,
    directSettingsConfig: DirectChatSettings = DirectChatSettings()
) extends ImRepository[F] {
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
      update(
        c,
        "INSERT INTO snap_flow.im_user_state(app_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",
        who.app,
        who.user
      )
    query(
      c,
      "SELECT cursor,message_seq,read_seq,retained_from,direct_unread,notification_unread FROM snap_flow.im_user_state WHERE app_id=? AND user_id=?" + (if (
                                                                                                                                                          lock
                                                                                                                                                        )
                                                                                                                                                          " FOR UPDATE"
                                                                                                                                                        else
                                                                                                                                                          ""),
      Seq(who.app, who.user)
    )(r => Position(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6))).headOption
      .getOrElse(Position(0, 0, 0, 0))
  }
  private def databaseNow(c: Connection): Long =
    query(c, "SELECT floor(extract(epoch FROM clock_timestamp()))::bigint", Nil)(_.getLong(1)).head
  private def notifyChange(c: Connection, who: Identity): Unit = {
    query(c, "SELECT pg_notify('polyglot_im_changes', ?)", Seq(RoutingKey(who)))(_ => ()); ()
  }
  private def message(r: ResultSet): StoredMessage = StoredMessage(
    r.getString("message_id"),
    r.getString("app_id"),
    r.getString("user_id"),
    r.getLong("seq"),
    r.getString("type"),
    parse(r.getString("payload")).toOption.get,
    r.getString("created_at"),
    isRead = r.getBoolean("is_read"),
    isDeleted = r.getBoolean("is_deleted"),
    stateVersion = r.getLong("state_version")
  )
  private def event(c: Connection, who: Identity, n: Long, kind: String, data: Json): Unit = {
    update(
      c,
      "INSERT INTO snap_flow.im_event(app_id,user_id,cursor,event_id,type,data,created_at) VALUES(?,?,?,?,?,?,?)",
      who.app,
      who.user,
      n,
      Protocol.id,
      kind,
      data.noSpaces,
      Protocol.time
    )
    update(c, "UPDATE snap_flow.im_user_state SET cursor=? WHERE app_id=? AND user_id=?", n, who.app, who.user)
    notifyChange(c, who)
  }

  private def directSchemaReady(c: Connection): Boolean = query(
    c,
    "SELECT to_regclass('snap_flow.im_direct_settings') IS NOT NULL AND to_regclass('snap_flow.im_direct_preference') IS NOT NULL AND to_regclass('snap_flow.im_direct_deleted') IS NOT NULL AND to_regclass('snap_flow.im_presence') IS NOT NULL AND to_regclass('snap_flow.im_direct_activation') IS NOT NULL AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='snap_flow' AND table_name='im_direct_message' AND column_name='state_version')",
    Nil
  )(_.getBoolean(1)).head

  /** 在数据库锁内初始化当前 snap_flow 表结构；重复启动保留已有数据，不执行旧版本升级。 */
  def initialize: F[Unit] = transaction { c =>
    val statement = c.createStatement()
    try {
      statement.execute("SELECT pg_advisory_xact_lock(674239011)")
      statement.execute("CREATE SCHEMA IF NOT EXISTS snap_flow")
      // im_schema：当前结构是否初始化的标记表，独立于业务消息及增量迁移。
      // version：只允许 1 的主键，存在该行表示初始化完成，不是可递增的迁移版本号。
      statement.execute("CREATE TABLE IF NOT EXISTS snap_flow.im_schema(version INTEGER PRIMARY KEY CHECK(version=1))")
      val initialized = query(c, "SELECT version FROM snap_flow.im_schema", Nil)(_.getInt(1)).nonEmpty
      if (!initialized) {
        val sql = scala.util.Using.resource(scala.io.Source.fromResource("db/schema.sql"))(_.mkString)
        sql.split(";").map(_.trim).filter(_.nonEmpty).foreach(statement.execute)
      }
      if (directSettingsConfig.enabled && !directSchemaReady(c))
        throw ApiError(503, Code.NotReady, Message.DirectUnavailable)
      val hasActivation =
        query(c, "SELECT to_regclass('snap_flow.im_direct_activation') IS NOT NULL", Nil)(_.getBoolean(1)).head
      if (directSettingsConfig.enabled)
        update(c, "INSERT INTO snap_flow.im_direct_activation(enabled) VALUES(TRUE) ON CONFLICT DO NOTHING")
      else if (
        hasActivation && query(c, "SELECT enabled FROM snap_flow.im_direct_activation WHERE enabled=TRUE", Nil)(
          _.getBoolean(1)
        ).headOption.getOrElse(false)
      ) throw ApiError(503, Code.NotReady, Message.DirectUnavailable)
    } finally statement.close()
  }

  /** 检查当前表结构已初始化且连接指向可写主库。 */
  def ready: F[Boolean] =
    reading(c =>
      (!directSettingsConfig.enabled || directSchemaReady(c)) && query(
        c,
        "SELECT version FROM snap_flow.im_schema WHERE version=1 AND NOT pg_is_in_recovery() AND (SELECT count(*) FROM information_schema.columns WHERE table_schema='snap_flow' AND ((table_name='im_user_state' AND column_name='notification_unread') OR (table_name='im_message' AND column_name IN ('is_read','is_deleted','state_version'))))=4",
        Nil
      )(
        _.getInt(1)
      ).nonEmpty
    )

  /** 以调用方已校验的服务身份幂等提交通知；返回消息及是否首次创建，同键不同内容拒绝写入。 */
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
      "SELECT * FROM snap_flow.im_message WHERE app_id=? AND sender_id=? AND client_key=?",
      Seq(sender.app, sender.user, command.key)
    )(r => (message(r), r.getString("fingerprint")))
    existing.headOption match {
      case Some((m, fp)) =>
        if (fp != fingerprint)
          throw ApiError(409, Code.IdempotencyConflict, Message.IdempotencyContentConflict)
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
          "INSERT INTO snap_flow.im_message(message_id,app_id,user_id,seq,sender_id,client_key,fingerprint,type,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
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
        update(
          c,
          "UPDATE snap_flow.im_user_state SET message_seq=?,notification_unread=notification_unread+1 WHERE app_id=? AND user_id=?",
          m.seq,
          m.app,
          m.user
        )
        event(c, recipient, current.cursor + 1, "message.created", m.json)
        val devices = query(
          c,
          "SELECT device_id FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND active=TRUE AND token IS NOT NULL",
          Seq(m.app, m.user)
        )(_.getString(1))
        val pushAt = databaseNow(c) + 3
        devices.foreach(device =>
          update(
            c,
            "INSERT INTO snap_flow.im_outbox(id,app_id,user_id,message_id,device_id,next_at) VALUES(?,?,?,?,?,?)",
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

  /** 事务内读取的单聊状态，包含双方成员、顺序和各自阅读水位。 */
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

    /** 返回此单聊的两个成员身份。 */
    def users: List[String] = List(low, high)

    /** 返回指定单聊成员的阅读水位，不修改数据库状态。 */
    def read(user: String): Long = if (user == low) lowRead else highRead
  }
  private def readDirectRow(r: ResultSet): Direct = Direct(
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
  private def direct(c: Connection, who: Identity, id: String, lock: Boolean = false): Direct =
    query(
      c,
      "SELECT * FROM snap_flow.im_direct_conversation WHERE app_id=? AND conversation_id=? AND (user_low=? OR user_high=?)" + (if (
                                                                                                                                 lock
                                                                                                                               ) " FOR UPDATE"
                                                                                                                               else
                                                                                                                                 ""),
      Seq(who.app, id, who.user, who.user)
    )(readDirectRow).headOption.getOrElse(throw ApiError(404, Code.NotFound, Message.ConversationNotFound))
  private def directMessage(r: ResultSet, user: String): StoredMessage = StoredMessage(
    r.getString("message_id"),
    r.getString("app_id"),
    user,
    r.getLong("seq"),
    r.getString("type"),
    parse(r.getString("payload")).toOption.get,
    r.getString("created_at"),
    Some(r.getString("conversation_id")),
    stateVersion = if (directSettingsConfig.enabled) r.getLong("state_version") else 0L
  )

  /** 按用户 ID 游标分页读取本人偏好，可只列黑名单；不读取或披露对方设置。 */
  def listPeerPreferences(who: Identity, after: String, limit: Int, blockedOnly: Boolean): F[Json] = reading { c =>
    controlsRequired()
    val rows = query(
      c,
      "SELECT peer_id,blocked,muted,pinned FROM snap_flow.im_direct_preference WHERE app_id=? AND user_id=? AND peer_id>? AND (NOT ? OR blocked) ORDER BY peer_id LIMIT ?",
      Seq(who.app, who.user, after, blockedOnly, limit + 1)
    ) { r =>
      Json.obj(
        "user_id" -> Json.fromString(r.getString(1)),
        "blocked" -> Json.fromBoolean(r.getBoolean(2)),
        "muted"   -> Json.fromBoolean(r.getBoolean(3)),
        "pinned"  -> Json.fromBoolean(r.getBoolean(4))
      )
    }
    val items = rows.take(limit)
    Json.obj(
      "items"      -> Json.arr(items*),
      "has_more"   -> Json.fromBoolean(rows.size > limit),
      "next_after" -> items.lastOption.fold(Json.Null)(j => j.hcursor.downField("user_id").focus.get)
    )
  }

  private def controlsRequired(): Unit =
    if (!directSettingsConfig.enabled) throw ApiError(503, Code.DirectUnavailable, Message.DirectUnavailable)

  private def pairLock(c: Connection, who: Identity, peer: String): Unit = {
    Protocol.bounded(peer, "peer_user_id")
    if (who.user == peer) throw ApiError(422, Code.InvalidArgument, Message.PeerRequired)
    query(
      c,
      "SELECT pg_advisory_xact_lock(hashtextextended(?,3))",
      Seq(Protocol.canonical(Json.arr((who.app :: List(who.user, peer).sorted).map(Json.fromString)*)))
    )(_ => ())
    ()
  }
  private def chatSettings(c: Connection, who: Identity, user: String): Json =
    query(
      c,
      "SELECT inbound_scope,online_visibility FROM snap_flow.im_direct_settings WHERE app_id=? AND user_id=?",
      Seq(who.app, user)
    ) { r =>
      Json.obj(
        "inbound_scope"     -> Json.fromString(r.getString(1)),
        "online_visibility" -> Json.fromString(r.getString(2))
      )
    }.headOption.getOrElse(
      Json.obj("inbound_scope" -> Json.fromString("mutual"), "online_visibility" -> Json.fromString("mutual"))
    )

  private def defaultChatPreference: Json = Json.obj(
    "blocked"        -> Json.False,
    "muted"          -> Json.False,
    "pinned"         -> Json.False,
    "hidden_through" -> Json.fromString("-1"),
    "clear_seq"      -> Json.fromString("0")
  )
  private def chatPreference(c: Connection, who: Identity, peer: String): Json =
    query(
      c,
      "SELECT blocked,muted,pinned,hidden_through,clear_seq FROM snap_flow.im_direct_preference WHERE app_id=? AND user_id=? AND peer_id=?",
      Seq(who.app, who.user, peer)
    ) { r =>
      Json.obj(
        "blocked"        -> Json.fromBoolean(r.getBoolean(1)),
        "muted"          -> Json.fromBoolean(r.getBoolean(2)),
        "pinned"         -> Json.fromBoolean(r.getBoolean(3)),
        "hidden_through" -> Json.fromString(r.getLong(4).toString),
        "clear_seq"      -> Json.fromString(r.getLong(5).toString)
      )
    }.headOption.getOrElse(defaultChatPreference)
  private def preferenceBoolean(value: Json, key: String): Boolean = value.hcursor.get[Boolean](key).getOrElse(false)
  private def preferenceSequence(value: Json, key: String): Long =
    value.hcursor.get[String](key).toOption.flatMap(_.toLongOption).getOrElse(0L)
  private def blockedPair(c: Connection, who: Identity, peer: String): Boolean =
    preferenceBoolean(chatPreference(c, who, peer), "blocked") || preferenceBoolean(
      chatPreference(c, who.copy(user = peer), who.user),
      "blocked"
    )
  private def allowedDirect(c: Connection, who: Identity, peer: String, relationship: PeerRelationship): Boolean =
    relationship.exists && !blockedPair(c, who, peer) && (Protocol.field(
      chatSettings(c, who, peer),
      "inbound_scope"
    ) match {
      case "all"       => true
      case "following" => relationship.followsYou
      case _           => relationship.mutual
    })
  private def authorizeDirect(
      c: Connection,
      who: Identity,
      peer: String,
      relationship: Option[PeerRelationship]
  ): Unit =
    if (directSettingsConfig.enabled) {
      pairLock(c, who, peer)
      val current = relationship.getOrElse(throw ApiError(503, Code.DirectUnavailable, Message.DirectUnavailable))
      if (!allowedDirect(c, who, peer, current)) throw ApiError(403, Code.DirectDenied, Message.DirectDenied)
    }

  private def visibleDirectMessages(
      c: Connection,
      who: Identity,
      d: Direct,
      messages: List[StoredMessage],
      pref: Json,
      deletedOverride: Option[Map[String, Long]] = None
  ): List[StoredMessage] =
    if (messages.isEmpty) Nil
    else {
      val ids = messages.map(_.id)
      val deleted = deletedOverride.getOrElse(
        query(
          c,
          s"SELECT message_id,state_version FROM snap_flow.im_direct_deleted WHERE app_id=? AND user_id=? AND conversation_id=? AND message_id IN (${ids.map(_ => "?").mkString(",")})",
          Seq(who.app, who.user, d.id) ++ ids
        )(r => r.getString(1) -> r.getLong(2)).toMap
      )
      val clear = preferenceSequence(pref, "clear_seq")
      messages.map(m =>
        m.copy(
          isDeleted = m.seq <= clear || deleted.contains(m.id),
          stateVersion = m.stateVersion.max(deleted.getOrElse(m.id, 0L))
        )
      )
    }

  /** 读取启用单聊控制后的本人权限设置；未配置业务目录时由服务层拒绝启用。 */
  def directSettings(who: Identity): F[Json] = reading { c =>
    controlsRequired(); chatSettings(c, who, who.user)
  }

  /** 仅接受受支持的私信范围及在线隐私值，不变更其他设置。 */
  def updateDirectSettings(who: Identity, data: Json): F[Json] = transaction { c =>
    controlsRequired()
    val scope = Protocol.field(data, "inbound_scope"); val online = Protocol.field(data, "online_visibility")
    if (
      !data.asObject
        .exists(_.keys.toSet == Set("inbound_scope", "online_visibility")) || !Set("all", "following", "mutual")(
        scope
      ) || !Set("mutual", "off")(online)
    ) throw ApiError(422, Code.InvalidArgument, Message.InvalidDirectSettings)
    update(
      c,
      "INSERT INTO snap_flow.im_direct_settings(app_id,user_id,inbound_scope,online_visibility) VALUES(?,?,?,?) ON CONFLICT(app_id,user_id) DO UPDATE SET inbound_scope=excluded.inbound_scope,online_visibility=excluded.online_visibility",
      who.app,
      who.user,
      scope,
      online
    )
    chatSettings(c, who, who.user)
  }

  /** 返回自己的偏好和可发送判定；拒绝原因不暴露对方拉黑及权限设置。 */
  def peerPreferences(who: Identity, peer: String, relationship: PeerRelationship): F[Json] = reading { c =>
    controlsRequired(); Protocol.bounded(peer, "peer_user_id")
    chatPreference(c, who, peer).deepMerge(
      Json.obj("can_send" -> Json.fromBoolean(allowedDirect(c, who, peer, relationship)))
    )
  }

  /** 拉黑、静音及置顶与发送共享用户对锁，偏好通过双方各自的会话快照同步。 */
  def updatePeerPreferences(who: Identity, peer: String, data: Json): F[Json] = transaction { c =>
    controlsRequired(); pairLock(c, who, peer)
    val fields = data.asObject.getOrElse(throw ApiError(422, Code.InvalidArgument, Message.InvalidDirectPreferences))
    if (fields.isEmpty || !fields.keys.forall(Set("blocked", "muted", "pinned")))
      throw ApiError(422, Code.InvalidArgument, Message.InvalidDirectPreferences)
    val previous = chatPreference(c, who, peer)
    def value(key: String): Boolean = fields(key).fold(preferenceBoolean(previous, key))(
      _.asBoolean.getOrElse(throw ApiError(422, Code.InvalidArgument, Message.booleanRequired(key)))
    )
    update(
      c,
      "INSERT INTO snap_flow.im_direct_preference(app_id,user_id,peer_id,blocked,muted,pinned) VALUES(?,?,?,?,?,?) ON CONFLICT(app_id,user_id,peer_id) DO UPDATE SET blocked=excluded.blocked,muted=excluded.muted,pinned=excluded.pinned",
      who.app,
      who.user,
      peer,
      value("blocked"),
      value("muted"),
      value("pinned")
    )
    query(
      c,
      "SELECT conversation_id FROM snap_flow.im_direct_conversation WHERE app_id=? AND user_low=? AND user_high=?",
      Seq(who.app, List(who.user, peer).min, List(who.user, peer).max)
    )(_.getString(1)).foreach { id =>
      val d = direct(c, who, id, true)
      update(
        c,
        "UPDATE snap_flow.im_direct_conversation SET revision=revision+1 WHERE app_id=? AND conversation_id=?",
        who.app,
        id
      )
      directEvents(c, who, direct(c, who, id))
    }
    chatPreference(c, who, peer)
  }

  /** 查询当前用户已加入的会话对方；服务层据此核对实时业务关系。 */
  def directPeer(who: Identity, id: String): F[String] = reading(c => direct(c, who, id).users.find(_ != who.user).get)

  private def recalculateDirect(c: Connection, who: Identity, d: Direct, messages: List[StoredMessage] = Nil): Json = {
    val counts = d.users.map { user =>
      val member = who.copy(user = user); val peer = d.users.find(_ != user).get
      val lower  = d.read(user).max(preferenceSequence(chatPreference(c, member, peer), "clear_seq"))
      val count = query(
        c,
        "SELECT count(*) FROM snap_flow.im_direct_message m WHERE m.app_id=? AND m.conversation_id=? AND m.seq>? AND m.sender_id<>? AND m.type<>'chat.recalled' AND NOT EXISTS(SELECT 1 FROM snap_flow.im_direct_deleted x WHERE x.app_id=m.app_id AND x.user_id=? AND x.message_id=m.message_id)",
        Seq(who.app, d.id, lower, user, user)
      )(_.getLong(1)).head
      user -> count
    }.toMap
    val delta = Map(d.low -> (counts(d.low) - d.lowUnread), d.high -> (counts(d.high) - d.highUnread))
    update(
      c,
      "UPDATE snap_flow.im_direct_conversation SET low_unread=?,high_unread=?,revision=revision+1 WHERE app_id=? AND conversation_id=?",
      counts(d.low),
      counts(d.high),
      who.app,
      d.id
    )
    val next = direct(c, who, d.id)
    directEvents(c, who, next, messages, delta)
    directJson(c, who, next)
  }

  /** 隐藏及清空以指定序号为边界，新消息不会被迟到请求误删；对方历史及已读回执不变。 */
  def updateDirectVisibility(who: Identity, id: String, through: Long, hide: Boolean, clear: Boolean): F[Json] =
    transaction { c =>
      controlsRequired()
      val initial                   = direct(c, who, id); val peer = initial.users.find(_ != who.user).get
      pairLock(c, who, peer); val d = direct(c, who, id, true)
      if (through < 0 || through > d.seq) throw ApiError(422, Code.InvalidArgument, Message.ReadPositionAhead)
      update(
        c,
        "INSERT INTO snap_flow.im_direct_preference(app_id,user_id,peer_id,hidden_through,clear_seq) VALUES(?,?,?,?,?) ON CONFLICT(app_id,user_id,peer_id) DO UPDATE SET hidden_through=GREATEST(snap_flow.im_direct_preference.hidden_through,excluded.hidden_through),clear_seq=GREATEST(snap_flow.im_direct_preference.clear_seq,excluded.clear_seq)",
        who.app,
        who.user,
        peer,
        if (hide) through else -1L,
        if (clear) through else 0L
      )
      recalculateDirect(c, who, d)
    }

  /** 删除只影响本人；撤回以数据库时间验证本人及两分钟窗口，保留服务端墓碑和原幂等指纹。 */
  def updateDirectMessage(who: Identity, id: String, messageId: String, recall: Boolean): F[Json] = transaction { c =>
    controlsRequired()
    val initial                   = direct(c, who, id); val peer = initial.users.find(_ != who.user).get
    pairLock(c, who, peer); val d = direct(c, who, id, true)
    val m = query(
      c,
      "SELECT * FROM snap_flow.im_direct_message WHERE app_id=? AND conversation_id=? AND message_id=?",
      Seq(who.app, id, messageId)
    )(r => directMessage(r, who.user)).headOption.getOrElse(throw ApiError(404, Code.NotFound, Message.MessageNotFound))
    if (recall) {
      if (Protocol.field(m.payload, "sender_id") != who.user)
        throw ApiError(403, Code.DirectDenied, Message.DirectDenied)
      if (m.kind != "chat.recalled") {
        if (java.time.Instant.parse(m.created).getEpochSecond + directSettingsConfig.recallSeconds < databaseNow(c))
          throw ApiError(409, Code.RecallExpired, Message.RecallExpired)
        update(
          c,
          "UPDATE snap_flow.im_direct_message SET type='chat.recalled',payload=?,state_version=? WHERE app_id=? AND conversation_id=? AND message_id=?",
          Json.obj("sender_id" -> Json.fromString(who.user)).noSpaces,
          d.revision + 1,
          who.app,
          id,
          messageId
        )
      }
    } else
      update(
        c,
        "INSERT INTO snap_flow.im_direct_deleted(app_id,user_id,conversation_id,message_id,state_version) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
        who.app,
        who.user,
        id,
        messageId,
        d.revision + 1
      )
    val changed = query(
      c,
      "SELECT * FROM snap_flow.im_direct_message WHERE app_id=? AND conversation_id=? AND message_id=?",
      Seq(who.app, id, messageId)
    )(r => directMessage(r, who.user)).head
    recalculateDirect(c, who, d, List(changed))
  }

  /** 在线租约仅接受活跃设备；注销设备即使尚未到期也不再视为在线。 */
  def updatePresence(who: Identity, device: String, online: Boolean): F[Unit] = transaction { c =>
    controlsRequired(); Protocol.bounded(device, "device_id")
    val active = query(
      c,
      "SELECT 1 FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE FOR UPDATE",
      Seq(who.app, who.user, device)
    )(_ => true).nonEmpty
    if (!active) throw ApiError(409, Code.DeviceSessionClosed, Message.DeviceSessionClosed)
    update(
      c,
      "INSERT INTO snap_flow.im_presence(app_id,user_id,device_id,lease_until) VALUES(?,?,?,?) ON CONFLICT(app_id,user_id,device_id) DO UPDATE SET lease_until=excluded.lease_until",
      who.app,
      who.user,
      device,
      if (online) databaseNow(c) + directSettingsConfig.presenceSeconds else 0L
    )
    ()
  }

  /** 互关、双方开启且未拉黑才返回租约状态；批量查询避免按用户访问数据库。 */
  def presence(who: Identity, relationships: Map[String, PeerRelationship]): F[Json] = reading { c =>
    controlsRequired()
    val peers = relationships.keys.toList.sorted
    if (peers.isEmpty || peers.size > 50) throw ApiError(422, Code.InvalidArgument, Message.InvalidDirectPreferences)
    val all          = (who.user :: peers).distinct
    val placeholders = all.map(_ => "?").mkString(",")
    val scopes = query(
      c,
      s"SELECT user_id,online_visibility FROM snap_flow.im_direct_settings WHERE app_id=? AND user_id IN ($placeholders)",
      Seq(who.app) ++ all
    )(r => r.getString(1) -> r.getString(2)).toMap
    val blocked = query(
      c,
      s"SELECT user_id,peer_id FROM snap_flow.im_direct_preference WHERE app_id=? AND blocked=TRUE AND ((user_id=? AND peer_id IN ($placeholders)) OR (peer_id=? AND user_id IN ($placeholders)))",
      Seq(who.app, who.user) ++ all ++ Seq(who.user) ++ all
    )(r => if (r.getString(1) == who.user) r.getString(2) else r.getString(1)).toSet
    val online = query(
      c,
      s"SELECT DISTINCT p.user_id FROM snap_flow.im_presence p JOIN snap_flow.im_device d USING(app_id,user_id,device_id) WHERE p.app_id=? AND p.user_id IN ($placeholders) AND p.lease_until>? AND d.active=TRUE",
      Seq(who.app) ++ all ++ Seq(databaseNow(c))
    )(_.getString(1)).toSet
    val enabled = scopes.getOrElse(who.user, "mutual") == "mutual"
    val items = peers.map { peer =>
      val visible =
        enabled && relationships(peer).mutual && !blocked(peer) && scopes.getOrElse(peer, "mutual") == "mutual"
      Json.obj(
        "user_id" -> Json.fromString(peer),
        "state"   -> Json.fromString(if (!visible) "hidden" else if (online(peer)) "online" else "offline")
      )
    }
    Json.obj("items" -> Json.arr(items*))
  }

  private def directJson(
      c: Connection,
      who: Identity,
      d: Direct,
      messages: List[StoredMessage] = Nil,
      preferenceOverride: Option[Json] = None,
      deletedOverride: Option[Map[String, Long]] = None
  ): Json = {
    val unread = if (who.user == d.low) d.lowUnread else d.highUnread
    val pref =
      if (directSettingsConfig.enabled)
        preferenceOverride.getOrElse(chatPreference(c, who, d.users.find(_ != who.user).get))
      else Json.obj()
    val rendered =
      if (directSettingsConfig.enabled) visibleDirectMessages(c, who, d, messages, pref, deletedOverride) else messages
    Json
      .obj(
        "conversation_id" -> Json.fromString(d.id),
        "kind"            -> Json.fromString("direct"),
        "member_ids"      -> Json.arr(d.users.map(Json.fromString)*),
        "revision"        -> Json.fromString(d.revision.toString),
        "latest_seq"      -> Json.fromString(d.seq.toString),
        "read_seq"        -> Json.fromString(d.read(who.user).toString),
        "peer_read_seq"   -> Json.fromString(d.read(d.users.find(_ != who.user).get).toString),
        "unread_count"    -> Json.fromString(unread.toString),
        "messages"        -> Json.arr(rendered.map(_.json)*)
      )
      .deepMerge(
        if (!directSettingsConfig.enabled) Json.obj()
        else pref.deepMerge(Json.obj("hidden" -> Json.fromBoolean(d.seq <= preferenceSequence(pref, "hidden_through"))))
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
          "UPDATE snap_flow.im_user_state SET direct_unread=direct_unread+? WHERE app_id=? AND user_id=?",
          delta,
          member.app,
          member.user
        )
      }
      event(c, member, pos.cursor + 1, "conversation.updated", directJson(c, member, d, messages))
    }
  }

  /** 按对方用户身份创建或取得唯一单聊会话。 */
  def createDirect(who: Identity, peer: String, relationship: Option[PeerRelationship] = None): F[Json] = transaction {
    c =>
      Protocol.bounded(peer, "peer_user_id")
      if (peer == who.user) throw ApiError(422, Code.InvalidArgument, Message.PeerRequired)
      authorizeDirect(c, who, peer, relationship)
      val users = List(who.user, peer).sorted
      val id    = "direct_" + Protocol.digest(Protocol.canonical(Json.arr((who.app :: users).map(Json.fromString)*)))
      val created = update(
        c,
        "INSERT INTO snap_flow.im_direct_conversation(app_id,conversation_id,user_low,user_high) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
        who.app,
        id,
        users.head,
        users.last
      ) > 0
      val d = direct(c, who, id, true)
      if (created) directEvents(c, who, d)
      else if (
        directSettingsConfig.enabled && preferenceSequence(chatPreference(c, who, peer), "hidden_through") >= 0
      ) {
        update(
          c,
          "UPDATE snap_flow.im_direct_preference SET hidden_through=-1 WHERE app_id=? AND user_id=? AND peer_id=?",
          who.app,
          who.user,
          peer
        )
        update(
          c,
          "UPDATE snap_flow.im_direct_conversation SET revision=revision+1 WHERE app_id=? AND conversation_id=?",
          who.app,
          id
        )
        directEvents(c, who, direct(c, who, id))
      }
      directJson(c, who, direct(c, who, id))
  }
  private def directList(c: Connection, who: Identity, after: String, limit: Int): (List[Json], Boolean) = {
    val rows = query(
      c,
      "SELECT * FROM snap_flow.im_direct_conversation WHERE app_id=? AND (user_low=? OR user_high=?) AND conversation_id>? ORDER BY conversation_id LIMIT ?",
      Seq(who.app, who.user, who.user, after, limit + 1)
    )(readDirectRow)
    val selected = rows.take(limit)
    if (selected.isEmpty) (Nil, false)
    else {
      val ids = selected.map(_.id); val placeholders = ids.map(_ => "?").mkString(",")
      val latest = query(
        c,
        s"SELECT DISTINCT ON(conversation_id) * FROM snap_flow.im_direct_message WHERE app_id=? AND conversation_id IN ($placeholders) ORDER BY conversation_id,seq DESC",
        Seq(who.app) ++ ids
      )(r => directMessage(r, who.user)).map(m => m.directConversation.get -> m).toMap
      val peers = selected.map(_.users.find(_ != who.user).get)
      val prefs =
        if (!directSettingsConfig.enabled) Map.empty[String, Json]
        else
          query(
            c,
            s"SELECT peer_id,blocked,muted,pinned,hidden_through,clear_seq FROM snap_flow.im_direct_preference WHERE app_id=? AND user_id=? AND peer_id IN (${peers.map(_ => "?").mkString(",")})",
            Seq(who.app, who.user) ++ peers
          ) { r =>
            r.getString(1) -> Json.obj(
              "blocked"        -> Json.fromBoolean(r.getBoolean(2)),
              "muted"          -> Json.fromBoolean(r.getBoolean(3)),
              "pinned"         -> Json.fromBoolean(r.getBoolean(4)),
              "hidden_through" -> Json.fromString(r.getLong(5).toString),
              "clear_seq"      -> Json.fromString(r.getLong(6).toString)
            )
          }.toMap
      val deleted =
        if (!directSettingsConfig.enabled || latest.isEmpty) Map.empty[String, Long]
        else
          query(
            c,
            s"SELECT message_id,state_version FROM snap_flow.im_direct_deleted WHERE app_id=? AND user_id=? AND message_id IN (${latest.values.map(_ => "?").mkString(",")})",
            Seq(who.app, who.user) ++ latest.values.map(_.id).toList
          )(r => r.getString(1) -> r.getLong(2)).toMap
      val rendered = selected.map { d =>
        val peer = d.users.find(_ != who.user).get
        val pref = prefs.getOrElse(peer, defaultChatPreference)
        directJson(c, who, d, latest.get(d.id).toList, Some(pref), Some(deleted))
      }
      (rendered, rows.size > limit)
    }
  }

  /** 从 after 位置分页取得当前用户可见的单聊会话摘要。 */
  def conversations(who: Identity, after: String, limit: Int): F[Json] = reading { c =>
    val (items, more) = directList(c, who, after, limit)
    Json.obj(
      "items"      -> Json.arr(items*),
      "has_more"   -> Json.fromBoolean(more),
      "next_after" -> items.lastOption.fold(Json.Null)(_.hcursor.downField("conversation_id").focus.get)
    )
  }

  /** 在同一快照中读取通知、会话摘要和水位，另返回会话是否还有后续页。 */
  def snapshotPage(who: Identity): F[(Position, List[StoredMessage], List[Json], Boolean)] = reading { c =>
    val pos = state(c, who)
    val rows = query(
      c,
      "SELECT * FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND NOT is_deleted ORDER BY seq DESC LIMIT 100",
      Seq(who.app, who.user)
    )(message)
    val (chats, more) = directList(c, who, "", 100)
    (pos, rows, chats, more)
  }

  /** 按 before 序号读取成员可见的单聊历史，并返回会话状态。 */
  def directHistory(who: Identity, id: String, before: Long, limit: Int): F[Json] = reading { c =>
    val d = direct(c, who, id)
    val rows = query(
      c,
      "SELECT * FROM snap_flow.im_direct_message WHERE app_id=? AND conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?",
      Seq(who.app, id, before, limit + 1)
    )(r => directMessage(r, who.user))
    Json.obj(
      "conversation" -> directJson(c, who, d, rows.take(limit)),
      "items" -> Json.arr(
        (if (directSettingsConfig.enabled)
           visibleDirectMessages(c, who, d, rows.take(limit), chatPreference(c, who, d.users.find(_ != who.user).get))
         else rows.take(limit)).map(_.json)*
      ),
      "has_more"        -> Json.fromBoolean(rows.size > limit),
      "next_before_seq" -> rows.take(limit).lastOption.fold(Json.Null)(m => Json.fromString(m.seq.toString))
    )
  }

  /** 在同一事务校验成员并幂等写入单聊消息、双方事件、计数和推送任务。 */
  def sendDirect(
      who: Identity,
      id: String,
      data: Json,
      relationship: Option[PeerRelationship] = None
  ): F[(StoredMessage, Boolean)] = transaction { c =>
    val key  = Protocol.bounded(Protocol.field(data, "client_message_id"), "client_message_id")
    val kind = Protocol.field(data, "type")
    if (kind != "chat.text") throw ApiError(422, Code.UnsupportedMessageType, Message.ChatTextRequired)
    val text = Protocol.field(data.hcursor.downField("payload").focus.getOrElse(Json.Null), "text")
    if (text.trim.isEmpty || text.codePointCount(0, text.length) > 2048 || text.indexOf(0.toChar) >= 0)
      throw ApiError(422, Code.InvalidArgument, Message.InvalidTextLength)
    // Sender and payload are server-authored; caller-supplied identity/attachments are rejected.
    val payloadInput = data.hcursor.downField("payload").focus.get
    if (payloadInput.asObject.forall(_.keys.toSet != Set("text")))
      throw ApiError(422, Code.InvalidArgument, Message.TextPayloadRequired)
    val payload = Json.obj("text" -> Json.fromString(text), "sender_id" -> Json.fromString(who.user))
    val fp      = Protocol.digest(id + "\n" + Protocol.canonical(payload))
    query(
      c,
      "SELECT pg_advisory_xact_lock(hashtextextended(?,2))",
      Seq(Protocol.canonical(Json.arr(List(who.app, who.user, key).map(Json.fromString)*)))
    )(_ => ())
    if (directSettingsConfig.enabled) {
      val peer = direct(c, who, id).users.find(_ != who.user).get
      authorizeDirect(c, who, peer, relationship)
    }
    val d = direct(c, who, id, true)
    val existing = query(
      c,
      "SELECT * FROM snap_flow.im_direct_message WHERE app_id=? AND sender_id=? AND client_key=?",
      Seq(who.app, who.user, key)
    )(r => (directMessage(r, who.user), r.getString("fingerprint")))
    existing.headOption match {
      case Some((m, fingerprint)) =>
        if (fingerprint != fp) throw ApiError(409, Code.IdempotencyConflict, Message.IdempotencyConflict)
        (m, false)
      case None =>
        val m = StoredMessage(Protocol.id, who.app, who.user, d.seq + 1, kind, payload, Protocol.time, Some(id))
        update(
          c,
          "INSERT INTO snap_flow.im_direct_message(message_id,app_id,conversation_id,seq,sender_id,client_key,fingerprint,type,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
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
          s"UPDATE snap_flow.im_direct_conversation SET message_seq=?,revision=revision+1,$unreadColumn=$unreadColumn+1 WHERE app_id=? AND conversation_id=?",
          m.seq,
          who.app,
          id
        )
        directEvents(c, who, direct(c, who, id), List(m), Map(peer -> 1L))
        val pushAt = databaseNow(c) + 3
        query(
          c,
          "SELECT device_id FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND active=TRUE AND token IS NOT NULL",
          Seq(who.app, peer)
        )(_.getString(1)).foreach { device =>
          update(
            c,
            "INSERT INTO snap_flow.im_outbox(id,app_id,user_id,message_id,device_id,next_at) VALUES(?,?,?,?,?,?)",
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

  /** 推进当前成员实际阅读位置；清空、个人删除和撤回不再重复扣减未读。 */
  def readDirect(who: Identity, id: String, seq: Long): F[Json] = transaction { c =>
    val d = direct(c, who, id, true)
    if (seq > d.seq) throw ApiError(422, Code.InvalidArgument, Message.ReadPositionAhead)
    if (seq > d.read(who.user)) {
      val column = if (who.user == d.low) "low_read" else "high_read"
      if (directSettingsConfig.enabled) {
        update(
          c,
          s"UPDATE snap_flow.im_direct_conversation SET $column=? WHERE app_id=? AND conversation_id=?",
          seq,
          who.app,
          id
        )
        // 保存旧计数，用更新后的阅读位置重算并计算准确差值。
        val newer = direct(c, who, id)
        recalculateDirect(c, who, newer)
      } else {
        val unreadColumn = if (who.user == d.low) "low_unread" else "high_unread"
        val cleared = query(
          c,
          "SELECT count(*) FROM snap_flow.im_direct_message WHERE app_id=? AND conversation_id=? AND seq>? AND seq<=? AND sender_id<>?",
          Seq(who.app, id, d.read(who.user), seq, who.user)
        )(_.getLong(1)).head
        update(
          c,
          s"UPDATE snap_flow.im_direct_conversation SET $column=?,revision=revision+1,$unreadColumn=$unreadColumn-? WHERE app_id=? AND conversation_id=?",
          seq,
          cleared,
          who.app,
          id
        )
        directEvents(c, who, direct(c, who, id), unreadDelta = Map(who.user -> -cleared))
      }
    }
    directJson(c, who, direct(c, who, id))
  }

  /** 按 before_seq 向前分页读取通知历史；历史页不推进增量游标。 */
  def history(who: Identity, before: Long, limit: Int): F[(List[StoredMessage], Boolean, Position)] = reading { c =>
    val rows = query(
      c,
      "SELECT * FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND NOT is_deleted AND seq<? ORDER BY seq DESC LIMIT ?",
      Seq(who.app, who.user, before, limit + 1)
    )(message)
    (rows.take(limit), rows.size > limit, state(c, who))
  }

  /** 按消息身份读取当前用户可见的通知或单聊消息，不向越权请求泄露存在性。 */
  def get(who: Identity, id: String): F[StoredMessage] = reading(c =>
    query(
      c,
      "SELECT * FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND NOT is_deleted AND message_id=?",
      Seq(who.app, who.user, id)
    )(
      message
    ).headOption
      .orElse(
        query(
          c,
          "SELECT m.* FROM snap_flow.im_direct_message m JOIN snap_flow.im_direct_conversation d USING(app_id,conversation_id) WHERE m.app_id=? AND m.message_id=? AND (d.user_low=? OR d.user_high=?)",
          Seq(who.app, id, who.user, who.user)
        )(r => directMessage(r, who.user)).headOption.flatMap { m =>
          if (!directSettingsConfig.enabled) Some(m)
          else {
            val d = direct(c, who, m.directConversation.get)
            visibleDirectMessages(c, who, d, List(m), chatPreference(c, who, d.users.find(_ != who.user).get))
              .find(!_.isDeleted)
          }
        }
      )
      .getOrElse(throw ApiError(404, Code.NotFound, Message.MessageNotFound))
  )

  /** 取得当前用户的消息、事件、阅读和留存水位。 */
  def position(who: Identity): F[Position] = reading(c => state(c, who))

  /** 在一致性读取中取得通知快照及其对应水位。 */
  def snapshot(who: Identity): F[(Position, List[StoredMessage])] = reading { c =>
    val pos = state(c, who)
    (
      pos,
      query(
        c,
        "SELECT * FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND NOT is_deleted ORDER BY seq DESC LIMIT 100",
        Seq(who.app, who.user)
      )(message)
    )
  }

  /** 在同一主库快照读取指定游标之后的事件；过期或超前游标拒绝读取，由客户端处理恢复。 */
  def sync(who: Identity, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    reading(c => syncAt(c, who, from, limit))

  /** 为活跃设备读取指定位置之后的事件，拒绝已退出的设备继续同步。 */
  def syncDevice(who: Identity, device: String, from: Long, limit: Int): F[(Position, List[(Long, Json)], Boolean)] =
    reading { c =>
      if (!deviceActive(c, who, device)) throw ApiError(401, Code.Unauthorized, Message.DeviceSessionRevoked)
      syncAt(c, who, from, limit)
    }
  private def syncAt(c: Connection, who: Identity, from: Long, limit: Int): (Position, List[(Long, Json)], Boolean) = {
    val pos = state(c, who)
    if (from < pos.retained) throw ApiError(410, Code.ResetRequired, Message.ResetRequired)
    if (from > pos.cursor) throw ApiError(400, Code.InvalidCursor, Message.CursorAhead)
    val events =
      if (from == pos.cursor) Nil
      else
        query(
          c,
          "SELECT cursor,event_id,type,data,created_at FROM snap_flow.im_event WHERE app_id=? AND user_id=? AND cursor>? ORDER BY cursor LIMIT ?",
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

  /** 在用户锁内校验全部归属并原子更新所选通知；墓碑保留发布幂等键及同步版本。 */
  def updateNotifications(who: Identity, ids: List[String], delete: Boolean): F[Position] = transaction { c =>
    if (
      ids.isEmpty || ids.size > 100 || ids.exists(id => id.isEmpty || id.length > 200) || ids.distinct.size != ids.size
    )
      throw ApiError(422, Code.InvalidArgument, Message.InvalidMessageIds)
    val pos   = state(c, who, true)
    val marks = ids.map(_ => "?").mkString(",")
    val rows = query(
      c,
      s"SELECT * FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND message_id IN ($marks)",
      Seq(who.app, who.user) ++ ids
    )(message)
    if (rows.size != ids.size) throw ApiError(404, Code.NotFound, Message.MessageNotFound)
    val changed = rows.filter(m => !m.isDeleted && (delete || (!m.isRead && m.seq > pos.read)))
    val cleared = changed.count(m => !m.isRead && m.seq > pos.read)
    changed.zipWithIndex.foreach { case (m, i) =>
      val revision = pos.cursor + i + 1
      update(
        c,
        "UPDATE snap_flow.im_message SET is_read=TRUE,is_deleted=?,state_version=? WHERE message_id=? AND app_id=? AND user_id=?",
        delete,
        revision,
        m.id,
        who.app,
        who.user
      )
      event(
        c,
        who,
        revision,
        "notification.updated",
        m.copy(isRead = true, isDeleted = delete, stateVersion = revision).json
      )
    }
    if (cleared > 0)
      update(
        c,
        "UPDATE snap_flow.im_user_state SET notification_unread=notification_unread-? WHERE app_id=? AND user_id=?",
        cleared,
        who.app,
        who.user
      )
    state(c, who)
  }

  /** 在事务内单调推进通知阅读水位，写入阅读事件并返回当前统计。 */
  def read(who: Identity, seq: Long): F[Position] = transaction { c =>
    val pos = state(c, who, true)
    if (seq > pos.seq) throw ApiError(422, Code.InvalidArgument, Message.ReadPositionAhead)
    if (seq > pos.read) {
      val cleared = query(
        c,
        "SELECT count(*) FROM snap_flow.im_message WHERE app_id=? AND user_id=? AND seq>? AND seq<=? AND NOT is_read AND NOT is_deleted",
        Seq(who.app, who.user, pos.read, seq)
      )(_.getLong(1)).head
      update(
        c,
        "UPDATE snap_flow.im_user_state SET read_seq=?,notification_unread=notification_unread-? WHERE app_id=? AND user_id=?",
        seq,
        cleared,
        who.app,
        who.user
      );
      event(c, who, pos.cursor + 1, "read.updated", Json.obj("read_seq" -> Json.fromString(seq.toString)))
    }
    state(c, who)
  }

  /** 创建或激活当前用户的设备会话，后续 token 更新必须属于活跃会话。 */
  def register(who: Identity, device: String): F[Unit] = transaction { c =>
    state(c, who, true)
    update(
      c,
      "INSERT INTO snap_flow.im_device(app_id,user_id,device_id,updated_at) VALUES(?,?,?,?) ON CONFLICT(app_id,user_id,device_id) DO UPDATE SET active=TRUE,updated_at=EXCLUDED.updated_at",
      who.app,
      who.user,
      device,
      Protocol.now
    ); ()
  }

  /** 设置活跃设备的 token、静音和正文预览偏好，避免迟到请求重新激活已退出设备。 */
  def device(who: Identity, device: String, token: Option[String], muted: Boolean, preview: Boolean): F[Unit] =
    patchDevice(who, device, token, Some(muted), Some(preview))

  /** 设置 token（None 表示解绑），保留未指定的静音和正文预览偏好。 */
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
      "SELECT active,muted,preview FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=? FOR UPDATE",
      Seq(who.app, who.user, device)
    )(r => (r.getBoolean(1), r.getBoolean(2), r.getBoolean(3))).headOption
    if (!existing.exists(_._1))
      throw ApiError(409, Code.DeviceSessionClosed, Message.DeviceSessionClosed)
    val prefs = existing.get
    token.foreach(t => update(c, "UPDATE snap_flow.im_device SET token=NULL WHERE app_id=? AND token=?", who.app, t))
    update(
      c,
      "UPDATE snap_flow.im_device SET token=?,muted=?,preview=?,updated_at=? WHERE app_id=? AND user_id=? AND device_id=?",
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

  /** 读取当前用户指定设备的推送偏好；未注册设备返回默认偏好，不激活会话。 */
  def preferences(who: Identity, device: String): F[Json] = reading { c =>
    query(
      c,
      "SELECT muted,preview FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(r =>
      Json.obj("muted" -> Json.fromBoolean(r.getBoolean(1)), "preview" -> Json.fromBoolean(r.getBoolean(2)))
    ).headOption.getOrElse(Json.obj("muted" -> Json.False, "preview" -> Json.False))
  }

  /** 停用当前设备并解绑推送 token，不影响其他账号或设备会话。 */
  def logout(who: Identity, device: String): F[Unit] = transaction { c =>
    state(c, who, true)
    update(
      c,
      "UPDATE snap_flow.im_device SET active=FALSE,token=NULL WHERE app_id=? AND user_id=? AND device_id=?",
      who.app,
      who.user,
      device
    );
    update(c, "DELETE FROM snap_flow.im_ticket WHERE app_id=? AND user_id=? AND device_id=?", who.app, who.user, device)
    notifyChange(c, who); ()
  }

  /** 检查设备是否仍属于当前用户的活跃会话。 */
  def active(who: Identity, device: String): F[Boolean] = reading(c =>
    query(
      c,
      "SELECT active FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(_.getBoolean(1)).headOption.contains(true)
  )

  /** 确认已完成本地提交的接收位置，不推进用户阅读水位。 */
  def ack(who: Identity, device: String, value: Long): F[Unit] = transaction { c =>
    val pos = state(c, who);
    if (value > pos.cursor) throw ApiError(422, Code.InvalidArgument, Message.InvalidDeliveryCursor);
    update(
      c,
      "UPDATE snap_flow.im_device SET delivered_cursor=GREATEST(delivered_cursor,?) WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE AND delivered_cursor<?",
      value,
      who.app,
      who.user,
      device,
      value
    ); ()
  }

  /** 为活跃设备签发短期一次性 WS 票据，绑定用户、应用和身份到期时间。 */
  def ticket(who: Identity, device: String): F[String] = transaction { c =>
    state(c, who, true)
    if (!deviceActive(c, who, device)) throw ApiError(401, Code.Unauthorized, Message.DeviceSessionRevoked)
    val now   = databaseNow(c)
    val token = Protocol.id + Protocol.id
    update(
      c,
      "INSERT INTO snap_flow.im_ticket(token_hash,app_id,user_id,device_id,expires_at,session_expires_at) VALUES(?,?,?,?,?,?)",
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
      "SELECT active FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=?",
      Seq(who.app, who.user, device)
    )(_.getBoolean(1)).headOption.contains(true)

  /** 原子消费一次性票据并返回其绑定身份与设备；过期或复用时拒绝。 */
  def consumeTicket(token: String): F[(Identity, String)] = transaction { c =>
    val hash = Protocol.digest(token)
    val candidate =
      query(
        c,
        "SELECT app_id,user_id,device_id,session_expires_at FROM snap_flow.im_ticket WHERE token_hash=?",
        Seq(hash)
      )(r => (Identity(r.getString(1), r.getString(2), "user", r.getLong(4)), r.getString(3))).headOption
        .getOrElse(throw ApiError(401, Code.InvalidTicket, Message.InvalidTicket))
    val (who, device) = candidate
    state(c, who, true)
    val deleted = update(c, "DELETE FROM snap_flow.im_ticket WHERE token_hash=? AND expires_at>?", hash, databaseNow(c))
    if (deleted != 1 || !deviceActive(c, who, device))
      throw ApiError(401, Code.InvalidTicket, Message.InvalidTicket)
    candidate
  }

  /** PostgreSQL 原子固定窗口限流；所有实例使用数据库时钟和同一个计数器。 */
  def allow(who: Identity, maximum: Int): F[Boolean] = transaction { c =>
    query(
      c,
      """INSERT INTO snap_flow.im_rate_limit(app_id,role,subject,window_start,hits)
      VALUES(?,?,?,floor(extract(epoch FROM clock_timestamp())/60)::bigint,1)
      ON CONFLICT(app_id,role,subject) DO UPDATE
      SET window_start=EXCLUDED.window_start,hits=CASE WHEN snap_flow.im_rate_limit.window_start=EXCLUDED.window_start THEN snap_flow.im_rate_limit.hits+1 ELSE 1 END
      WHERE snap_flow.im_rate_limit.window_start<>EXCLUDED.window_start OR snap_flow.im_rate_limit.hits<? RETURNING hits""",
      Seq(who.app, who.role, who.user, maximum)
    )(_.getInt(1)).nonEmpty
  }

  /** 只领取马上可执行的任务；claimToken 隔离过期 Worker 的回写。 */
  def claim: F[List[PushJob]] = claimBatch(25, 60)

  /** 有界领取到期任务并分配独立租约标识；并发 Worker 不重复拥有同一领取。 */
  def claimBatch(batchSize: Int, leaseSeconds: Int): F[List[PushJob]] = transaction { c =>
    require(batchSize > 0 && batchSize <= 256 && leaseSeconds > 0)
    val now = databaseNow(c)
    update(
      c,
      "UPDATE snap_flow.im_outbox SET state='dead',lease_token=NULL,error=COALESCE(NULLIF(error,''),'retry budget exhausted') WHERE state='pending' AND attempts>=10 AND lease_until<=?",
      now
    )
    val claimToken = Protocol.id
    query(
      c,
      """WITH selected AS (
      SELECT id FROM snap_flow.im_outbox WHERE state='pending' AND next_at<=? AND lease_until<=?
      ORDER BY next_at,id LIMIT ? FOR UPDATE SKIP LOCKED)
      UPDATE snap_flow.im_outbox j SET lease_until=?,lease_token=?,attempts=j.attempts+1
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

  /** 投递前重新核对消息、设备、静音和已读状态；无需投递时返回空结果。 */
  def pushTarget(job: PushJob): F[Option[PushTarget]] = transaction { c =>
    val who = Identity(job.app, job.user, "user", Long.MaxValue)
    val pos = state(c, who)
    val messages = query(
      c,
      "SELECT * FROM snap_flow.im_message WHERE message_id=? AND app_id=? AND user_id=? AND NOT is_deleted AND NOT is_read",
      Seq(job.message, job.app, job.user)
    )(message)
    val devices = query(
      c,
      "SELECT token,preview FROM snap_flow.im_device WHERE app_id=? AND user_id=? AND device_id=? AND active=TRUE AND muted=FALSE AND token IS NOT NULL",
      Seq(job.app, job.user, job.device)
    )(r => (r.getString(1), r.getBoolean(2)))
    val directTarget = query(
      c,
      "SELECT m.* FROM snap_flow.im_direct_message m JOIN snap_flow.im_direct_conversation d USING(app_id,conversation_id) WHERE m.app_id=? AND m.message_id=? AND ((d.user_low=? AND m.seq>d.low_read) OR (d.user_high=? AND m.seq>d.high_read)) AND m.sender_id<>?",
      Seq(who.app, job.message, who.user, who.user, who.user)
    )(r => directMessage(r, who.user)).headOption.filter { m =>
      if (!directSettingsConfig.enabled) true
      else {
        val d    = direct(c, who, m.directConversation.get); val peer = d.users.find(_ != who.user).get
        val pref = chatPreference(c, who, peer)
        m.kind != "chat.recalled" && !preferenceBoolean(pref, "muted") && !blockedPair(
          c,
          who,
          peer
        ) && visibleDirectMessages(c, who, d, List(m), pref).exists(!_.isDeleted)
      }
    }
    for {
      m <- messages.headOption.filter(_.seq > pos.read).orElse(directTarget); d <- devices.headOption
    } yield PushTarget(
      d._1,
      d._2,
      pos.unread + pos.directUnread,
      m
    )
  }

  /** 按领取标识回写推送结果；过期 Worker 不能覆盖新租约或误清新 token。 */
  def finish(job: PushJob, result: PushResult, token: Option[String]): F[Unit] = transaction { c =>
    val now = databaseNow(c)
    val owned = query(
      c,
      "SELECT id FROM snap_flow.im_outbox WHERE id=? AND lease_token=? AND lease_until>? AND state='pending' FOR UPDATE",
      Seq(job.id, job.claimToken, now)
    )(_.getString(1)).nonEmpty
    if (owned) {
      if (result.invalidToken)
        token.foreach(t =>
          update(
            c,
            "UPDATE snap_flow.im_device SET token=NULL WHERE app_id=? AND user_id=? AND device_id=? AND token=?",
            job.app,
            job.user,
            job.device,
            t
          )
        )
      val status = if (result.retry) {
        if (job.attempts >= 10) "dead" else "pending"
      } else if (result.rejected || result.invalidToken) "dead"
      else "done"
      update(
        c,
        "UPDATE snap_flow.im_outbox SET state=?,lease_until=0,lease_token=NULL,next_at=?,error=? WHERE id=? AND lease_token=?",
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
    if (directSettingsConfig.enabled) update(c, "DELETE FROM snap_flow.im_presence WHERE lease_until<?", databaseNow(c))
    val elected = query(c, "SELECT pg_try_advisory_xact_lock(674239012)", Nil)(_.getBoolean(1)).head
    if (elected) {
      val now = databaseNow(c)
      update(
        c,
        "DELETE FROM snap_flow.im_ticket WHERE token_hash IN (SELECT token_hash FROM snap_flow.im_ticket WHERE expires_at<? LIMIT 1000)",
        now
      )
      update(
        c,
        "DELETE FROM snap_flow.im_rate_limit WHERE window_start < floor(extract(epoch FROM clock_timestamp())/60)::bigint - 2 AND (app_id,role,subject) IN (SELECT app_id,role,subject FROM snap_flow.im_rate_limit WHERE window_start<? LIMIT 1000)",
        now / 60 - 2
      )
    }
    ()
  }

  /** 按截止时间清理可靠事件并更新留存水位，使旧游标走显式快照恢复。 */
  def purgeEvents(beforeEpochSeconds: Long): F[Unit] = transaction { c =>
    // 运维入口：逐用户锁定再清理，保留水位以明确触发 RESET_REQUIRED。
    val users =
      query(c, "SELECT app_id,user_id FROM snap_flow.im_user_state", Nil)(r => (r.getString(1), r.getString(2)))
    users.sortBy(identity).foreach { case (app, user) =>
      val who = Identity(app, user, "user", Long.MaxValue); state(c, who, true)
      val n = query(
        c,
        "SELECT COALESCE(MAX(cursor),0) FROM snap_flow.im_event WHERE app_id=? AND user_id=? AND created_at::timestamptz<to_timestamp(?)",
        Seq(app, user, beforeEpochSeconds)
      )(_.getLong(1)).head
      if (n > 0) {
        update(c, "DELETE FROM snap_flow.im_event WHERE app_id=? AND user_id=? AND cursor<=?", app, user, n);
        update(
          c,
          "UPDATE snap_flow.im_user_state SET retained_from=GREATEST(retained_from,?) WHERE app_id=? AND user_id=?",
          n,
          app,
          user
        )
      }
    }
  }
}

/** 创建配置了连接和 SQL 时限的 PostgreSQL 连接池，并在退出时关闭。 */
object PostgresRepository {

  /** 创建并校验 PostgreSQL 连接池，配置 SQL 时限；作用域结束时关闭池。 */
  def resource[F[_]: Async](url: String, user: String, password: String): Resource[F, PostgresRepository[F]] =
    resource(DatabaseSettings(url, user, password))

  /** 创建并校验 PostgreSQL 连接池，配置 SQL 时限；作用域结束时关闭池。 */
  def resource[F[_]: Async](
      config: DatabaseSettings,
      directChat: DirectChatSettings = DirectChatSettings()
  ): Resource[F, PostgresRepository[F]] =
    dataSourceResource[F](config).map(source => new PostgresRepository[F](source, directChat))

  /** 创建与服务端一致的受管理连接池，供组合根接入池观测；退出时关闭全部连接。 */
  def dataSourceResource[F[_]: Async](config: DatabaseSettings): Resource[F, HikariDataSource] = Resource
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
}
