package polyglot.im.infrastructure.store

import java.sql.Connection
import javax.sql.DataSource
import cats.effect.Async
import io.circe.parser.parse

import polyglot.im.domain.*
import polyglot.im.ports.TransactionalOutbox

/** PostgreSQL 业务端 Outbox；enqueue 加入宿主事务，绝不自行提交业务事务。 */
final class PostgresOutbox[F[_]: Async](source: DataSource) extends TransactionalOutbox[F, Connection] {
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
    // im_producer_outbox：业务宿主事务内记录的通知意图，与服务端 APNs Outbox 分开。
    // event_id：稳定业务事件幂等键，同键必须对应相同接收方、类型及载荷。
    // recipient：通知接收用户标识。
    // kind：待发布的通知类型。
    // payload：规范化 JSON 文本载荷，用于同键内容核对。
    // state：pending、done 或 dead，由派发结果驱动，不表示设备送达。
    // attempts：已提交派发结果的次数，complete 成功时递增，不是领取次数。
    // next_at：下次可领取时间，Unix 秒，初始 0 表示立即可领。
    // lease_until：领取租约到期时间，Unix 秒，过期后可重新领取。
    // lease：本次领取的随机标识，complete 仅接受仍匹配的领取者。
    // error：最近一次派发结果的错误说明，可为空。
    update(
      c,
      "CREATE TABLE IF NOT EXISTS snap_flow.im_producer_outbox(event_id TEXT PRIMARY KEY,recipient TEXT NOT NULL,kind TEXT NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',attempts INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL DEFAULT 0,lease_until BIGINT NOT NULL DEFAULT 0,lease TEXT,error TEXT)"
    ); ()
  }

  /** 返回在阻塞线程执行的入队效果；宿主须在提交前顺序执行，不并发共享连接，不由此方法提交事务。 */
  def enqueue(c: Connection, event: NotificationIntent): F[Unit] = Async[F].blocking {
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

  /** 按数据库时钟原子领取到期记录并提交新租约，不占用发送期间的事务。 */
  def claim(leaseSeconds: Long): F[Option[OutboxClaim]] = transaction { c =>
    require(leaseSeconds > 0, "leaseSeconds must be positive")
    val s = c.prepareStatement(
      "SELECT event_id,recipient,kind,payload,attempts FROM snap_flow.im_producer_outbox WHERE state='pending' AND next_at<=extract(epoch from clock_timestamp()) AND lease_until<=extract(epoch from clock_timestamp()) ORDER BY event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
    )
    try {
      val r = s.executeQuery();
      try
        if (!r.next()) None
        else {
          val row = OutboxClaim(
            NotificationIntent(r.getString(1), r.getString(2), r.getString(3), parse(r.getString(4)).toOption.get),
            r.getInt(5),
            java.util.UUID.randomUUID().toString
          )
          update(
            c,
            "UPDATE snap_flow.im_producer_outbox SET lease_until=floor(extract(epoch from clock_timestamp()))::bigint+?,lease=? WHERE event_id=?",
            leaseSeconds,
            row.lease,
            row.event.eventId
          )
          Some(row)
        }
      finally r.close()
    } finally s.close()
  }

  /** 按原租约提交派发结果；旧领取者不能覆盖后续领取者的状态。 */
  def complete(row: OutboxClaim, result: OutboxCompletion): F[Boolean] = transaction { c =>
    update(
      c,
      "UPDATE snap_flow.im_producer_outbox SET state=?,attempts=attempts+1,next_at=floor(extract(epoch from clock_timestamp()))::bigint+?,lease_until=0,lease=NULL,error=? WHERE event_id=? AND lease=?",
      result.state.value,
      result.retryDelaySeconds,
      result.error.orNull,
      row.event.eventId,
      row.lease
    ) == 1
  }
}
