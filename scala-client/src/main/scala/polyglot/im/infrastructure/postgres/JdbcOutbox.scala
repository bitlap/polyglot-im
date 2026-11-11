package polyglot.im.infrastructure.postgres

import java.sql.Connection
import javax.sql.DataSource
import cats.effect.Async
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse

import polyglot.im.domain.*
import polyglot.im.ports.NotificationPublisher

/** PostgreSQL 业务端 Outbox；enqueue 加入宿主事务，绝不自行提交业务事务。 */
final class JdbcOutbox[F[_]: Async](source: DataSource) {
  private def transaction[A](run: Connection => A): F[A] = Async[F].blocking {
    val c = source.getConnection
    try {
      c.setAutoCommit(false);
      try { val value = run(c); c.commit(); value }
      catch { case e: Throwable => c.rollback(); throw e }
    } finally c.close()
  }
  private def update(c: Connection, sql: String, values: Any*): Int = {
    val s = c.prepareStatement(sql)
    try { values.zipWithIndex.foreach((v, i) => s.setObject(i + 1, v)); s.executeUpdate() }
    finally s.close()
  }

  /** 在初始化锁内创建业务 Outbox 的 schema 和表，不连接其他业务数据库。 */
  def initialize: F[Unit] = transaction { c =>
    val lock = c.prepareStatement("SELECT pg_advisory_xact_lock(674239011)")
    try { lock.execute(); () }
    finally lock.close()
    update(c, "CREATE SCHEMA IF NOT EXISTS snap_flow")
    update(
      c,
      "CREATE TABLE IF NOT EXISTS snap_flow.im_producer_outbox(event_id TEXT PRIMARY KEY,recipient TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL DEFAULT 0,lease_until BIGINT NOT NULL DEFAULT 0,lease TEXT,error TEXT)"
    ); ()
  }

  /** 在 Async.blocking 中与业务 SQL 一起执行；调用方负责事务。 */
  def enqueue(c: Connection, event: NotificationIntent): Unit = {
    require(!c.getAutoCommit, "enqueue requires an active caller-owned transaction")
    require(event.eventId.nonEmpty, "stable event id required")
    val canonical = io.circe.Printer.noSpaces.copy(sortKeys = true).print(event.payload)
    update(
      c,
      "INSERT INTO snap_flow.im_producer_outbox(event_id,recipient,kind,payload) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
      event.eventId,
      event.recipient,
      event.kind,
      canonical
    )
    val s = c.prepareStatement("SELECT recipient,kind,payload FROM snap_flow.im_producer_outbox WHERE event_id=?")
    try {
      s.setString(1, event.eventId); val r = s.executeQuery();
      try
        require(
          r.next() && r.getString(1) == event.recipient && r.getString(2) == event.kind && r.getString(3) == canonical,
          "event id reused for different notification"
        )
      finally r.close()
    } finally s.close()
  }

  /** 本次领取的业务 Outbox 事件和租约，用于阻止过期确认覆盖新领取。 */
  private case class Claimed(event: NotificationIntent, attempts: Int, lease: String)
  private def claim: F[Option[Claimed]] = transaction { c =>
    val now = java.time.Instant.now().getEpochSecond
    val s = c.prepareStatement(
      "SELECT event_id,recipient,kind,payload,attempts FROM snap_flow.im_producer_outbox WHERE state='pending' AND next_at<=? AND lease_until<=? ORDER BY event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    try {
      s.setLong(1, now); s.setLong(2, now); val r = s.executeQuery();
      try
        if (!r.next()) None
        else {
          val row = Claimed(
            NotificationIntent(r.getString(1), r.getString(2), r.getString(3), parse(r.getString(4)).toOption.get),
            r.getInt(5),
            java.util.UUID.randomUUID().toString
          )
          update(
            c,
            "UPDATE snap_flow.im_producer_outbox SET lease_until=?,lease=? WHERE event_id=?",
            now + 120,
            row.lease,
            row.event.eventId
          )
          Some(row)
        }
      finally r.close()
    } finally s.close()
  }
  private def finish(row: Claimed, state: String, error: Option[String]): F[Unit] = transaction { c =>
    val next = java.time.Instant.now().getEpochSecond + math.min(300L, 1L << math.min(row.attempts, 9))
    update(
      c,
      "UPDATE snap_flow.im_producer_outbox SET state=?,attempts=attempts+1,next_at=?,lease_until=0,lease=NULL,error=? WHERE event_id=? AND lease=?",
      state,
      next,
      error.orNull,
      row.event.eventId,
      row.lease
    ); ()
  }

  /** 宿主调度调用；取消不确认，租约到期后使用相同事件 ID 重放。 */
  def drainOnce(sender: NotificationPublisher[F]): F[Boolean] = claim.flatMap {
    case None => Async[F].pure(false)
    case Some(row) =>
      sender
        .send(row.event.recipient, row.event.eventId, row.event.kind, row.event.payload)
        .attempt
        .flatMap {
          case Right(_)                                                   => finish(row, "sent", None)
          case Left(e: ImError) if Set(400, 403, 409, 413, 422)(e.status) => finish(row, "dead", Some(e.code))
          case Left(_: java.io.IOException) => finish(row, "pending", Some("NETWORK_ERROR"))
          case Left(e: ImError)             => finish(row, "pending", Some(e.code))
          case Left(e)                      => Async[F].raiseError(e)
        }
        .as(true)
  }
}
