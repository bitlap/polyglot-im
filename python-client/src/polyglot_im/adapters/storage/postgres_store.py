"""可注入的 PostgreSQL 客户端缓存，与业务事务 Outbox 分开。"""

from __future__ import annotations

import json
import re
from contextlib import asynccontextmanager
from typing import TYPE_CHECKING, Any

from ...domain.cache import apply_page, empty_cache
from ...domain.identity import Scope

if TYPE_CHECKING:
    from psycopg_pool import AsyncConnectionPool


class PostgresStore:
    """借用宿主池；按 namespace / scope 保存消息、会话和水位，不关闭宿主资源。"""

    def __init__(
        self,
        pool: AsyncConnectionPool,
        *,
        namespace: str,
        schema: str = "snap_flow",
        lock_timeout_ms: int = 15000,
    ):
        """namespace 隔离缓存使用者；schema 为标识符，锁等待必须为正整数毫秒。"""
        if not isinstance(namespace, str) or not namespace.strip() or len(namespace) > 200:
            raise ValueError("namespace must contain 1..200 characters")
        if not isinstance(schema, str) or re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", schema) is None:
            raise ValueError("schema must be a PostgreSQL identifier")
        if (
            isinstance(lock_timeout_ms, bool)
            or not isinstance(lock_timeout_ms, int)
            or lock_timeout_ms <= 0
        ):
            raise ValueError("lock_timeout_ms must be a positive integer")
        self.pool = pool
        self.namespace = namespace
        self.schema = schema
        self.lock_timeout_ms = lock_timeout_ms
        self._table = f"{schema}.im_client_cache"

    async def _timeout(self, connection):
        await connection.execute(
            "SELECT set_config('lock_timeout', %s, true)", (f"{self.lock_timeout_ms}ms",)
        )

    async def initialize(self) -> None:
        """显式创建缓存表；初始化事务持有跨实例 DDL 锁，失败回滚并归还连接。"""
        async with self.pool.connection() as connection, connection.transaction():
            await self._timeout(connection)
            await connection.execute("SELECT pg_advisory_xact_lock(674239012)")
            await connection.execute(f"CREATE SCHEMA IF NOT EXISTS {self.schema}")
            await connection.execute(
                f"CREATE TABLE IF NOT EXISTS {self._table}("
                "namespace TEXT NOT NULL,scope TEXT NOT NULL,data TEXT NOT NULL,"
                "PRIMARY KEY(namespace,scope))"
            )

    async def _read(self, connection, scope: Scope) -> dict[str, Any]:
        from psycopg.rows import tuple_row

        async with connection.cursor(row_factory=tuple_row) as cursor:
            await cursor.execute(
                f"SELECT data FROM {self._table} WHERE namespace=%s AND scope=%s",
                (self.namespace, scope.key),
            )
            row = await cursor.fetchone()
            return json.loads(row[0]) if row else empty_cache()

    async def load(self, scope: Scope) -> dict[str, Any]:
        """读取已提交缓存，不占写锁；未保存的作用域返回空缓存。"""
        async with self.pool.connection() as connection:
            return await self._read(connection, scope)

    async def _lock(self, connection, scope: Scope):
        await self._timeout(connection)
        await connection.execute(
            "SELECT pg_advisory_xact_lock(hashtextextended(%s,674239012))",
            (self.namespace + "\n" + scope.key,),
        )

    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]:
        """跨实例串行归并并原子提交；失败回滚，取消按驱动事务语义清理。"""
        async with self.pool.connection() as connection, connection.transaction():
            await connection.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
            await self._lock(connection, scope)
            value = apply_page(await self._read(connection, scope), page, reset)
            await connection.execute(
                f"INSERT INTO {self._table}(namespace,scope,data) VALUES(%s,%s,%s) "
                "ON CONFLICT(namespace,scope) DO UPDATE SET data=excluded.data",
                (self.namespace, scope.key, json.dumps(value, allow_nan=False, ensure_ascii=False)),
            )
            return value

    async def clear(self, scope: Scope) -> None:
        """与提交共用作用域锁，只删除目标缓存；损坏 JSON 也可清理。"""
        async with self.pool.connection() as connection, connection.transaction():
            await connection.execute("SET TRANSACTION ISOLATION LEVEL READ COMMITTED")
            await self._lock(connection, scope)
            await connection.execute(
                f"DELETE FROM {self._table} WHERE namespace=%s AND scope=%s",
                (self.namespace, scope.key),
            )


@asynccontextmanager
async def open_postgres_store(
    conninfo: str,
    *,
    namespace: str,
    schema: str = "snap_flow",
    min_size: int = 1,
    max_size: int = 8,
    timeout: float = 15,
    lock_timeout_ms: int = 15000,
):
    """显式拥有异步池并初始化缓存；退出或初始化失败均关闭池，DSN/TLS 由宿主配置。"""
    from psycopg_pool import AsyncConnectionPool

    if not conninfo.strip() or timeout <= 0:
        raise ValueError("explicit PostgreSQL settings and positive timeout required")
    pool = AsyncConnectionPool(
        conninfo, min_size=min_size, max_size=max_size, timeout=timeout, open=False
    )
    try:
        store = PostgresStore(
            pool, namespace=namespace, schema=schema, lock_timeout_ms=lock_timeout_ms
        )
        await pool.open(wait=True, timeout=timeout)
        await store.initialize()
        yield store
    finally:
        await pool.close()
