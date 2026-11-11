"""PostgreSQL 业务事务 Outbox；异步借用宿主连接池与业务事务。"""

from __future__ import annotations

import json
import uuid
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING

from ...common.errors import ImError
from ...ports.notifications import NotificationPublisher

if TYPE_CHECKING:
    from psycopg import AsyncConnection
    from psycopg_pool import AsyncConnectionPool


class PostgresOutbox:
    """借用连接池，不打开或关闭它；enqueue 不提交调用方事务。"""

    def __init__(self, pool: AsyncConnectionPool, *, lease_seconds: int = 120):
        if (
            isinstance(lease_seconds, bool)
            or not isinstance(lease_seconds, int)
            or lease_seconds <= 0
        ):
            raise ValueError("lease_seconds must be a positive integer")
        self.pool = pool
        self.lease_seconds = lease_seconds

    async def initialize(self) -> None:
        async with self.pool.connection() as connection, connection.transaction():
            await connection.execute("SELECT pg_advisory_xact_lock(674239012)")
            await connection.execute(
                "CREATE TABLE IF NOT EXISTS im_producer_outbox("
                "event_id TEXT PRIMARY KEY,recipient TEXT NOT NULL,kind TEXT NOT NULL,"
                "payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',"
                "attempts INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL DEFAULT 0,"
                "lease_until BIGINT NOT NULL DEFAULT 0,lease TEXT,error TEXT)"
            )

    @staticmethod
    async def enqueue(
        connection: AsyncConnection, event_id: str, recipient: str, kind: str, payload: dict
    ) -> None:
        from psycopg.pq import TransactionStatus
        from psycopg.rows import tuple_row

        if connection.info.transaction_status != TransactionStatus.INTRANS:
            raise ValueError("enqueue requires the caller's active business transaction")
        if not event_id:
            raise ValueError("stable event_id required")
        encoded = json.dumps(
            payload, sort_keys=True, separators=(",", ":"), allow_nan=False, ensure_ascii=False
        )
        async with connection.cursor(row_factory=tuple_row) as cursor:
            await cursor.execute(
                "INSERT INTO im_producer_outbox(event_id,recipient,kind,payload) "
                "VALUES(%s,%s,%s,%s) ON CONFLICT DO NOTHING",
                (event_id, recipient, kind, encoded),
            )
            await cursor.execute(
                "SELECT recipient,kind,payload FROM im_producer_outbox WHERE event_id=%s",
                (event_id,),
            )
            if await cursor.fetchone() != (recipient, kind, encoded):
                raise ValueError("event_id reused for different notification")

    async def _claim(self):
        from psycopg.rows import tuple_row

        lease = str(uuid.uuid4())
        async with self.pool.connection() as connection, connection.transaction():
            async with connection.cursor(row_factory=tuple_row) as cursor:
                await cursor.execute(
                    "SELECT event_id,recipient,kind,payload,attempts FROM im_producer_outbox "
                    "WHERE state='pending' AND next_at<=extract(epoch from clock_timestamp()) "
                    "AND lease_until<=extract(epoch from clock_timestamp()) "
                    "ORDER BY event_id LIMIT 1 FOR UPDATE SKIP LOCKED"
                )
                row = await cursor.fetchone()
                if row:
                    await cursor.execute(
                        "UPDATE im_producer_outbox SET "
                        "lease_until=floor(extract(epoch from clock_timestamp()))::bigint+%s,"
                        "lease=%s WHERE event_id=%s",
                        (self.lease_seconds, lease, row[0]),
                    )
                    return (*row, lease)
        return None

    async def _finish(self, row, state: str, error: str | None):
        async with self.pool.connection() as connection, connection.transaction():
            await connection.execute(
                "UPDATE im_producer_outbox SET state=%s,attempts=attempts+1,"
                "next_at=floor(extract(epoch from clock_timestamp()))::bigint+%s,"
                "lease_until=0,lease=NULL,error=%s WHERE event_id=%s AND lease=%s",
                (state, min(300, 2 ** min(row[4], 9)), error, row[0], row[5]),
            )

    async def drain_once(self, sender: NotificationPublisher) -> bool:
        row = await self._claim()
        if row is None:
            return False
        try:
            await sender.send(row[1], row[0], row[2], json.loads(row[3]))
        except ImError as error:
            dead = error.status in {400, 403, 409, 413, 422}
            await self._finish(row, "dead" if dead else "pending", error.code)
        except (OSError, TimeoutError):
            await self._finish(row, "pending", "NETWORK_ERROR")
        else:
            await self._finish(row, "sent", None)
        # 取消/进程退出不确认；租约到期后使用相同事件 ID 重放。
        return True


@asynccontextmanager
async def open_postgres_outbox(
    conninfo: str,
    *,
    min_size: int = 1,
    max_size: int = 8,
    timeout: float = 15,
    lease_seconds: int = 120,
):
    """显式拥有异步池；DSN 支持 host/dbname/user/password/sslmode 等 libpq 参数。"""
    from psycopg_pool import AsyncConnectionPool

    if not conninfo.strip():
        raise ValueError("explicit PostgreSQL connection settings required")
    if timeout <= 0:
        raise ValueError("timeout must be positive")
    pool = AsyncConnectionPool(
        conninfo, min_size=min_size, max_size=max_size, timeout=timeout, open=False
    )
    outbox = PostgresOutbox(pool, lease_seconds=lease_seconds)
    try:
        await pool.open(wait=True, timeout=timeout)
        yield outbox
    finally:
        await pool.close()
