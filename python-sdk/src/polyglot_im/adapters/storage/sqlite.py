import asyncio
import json
import sqlite3
from pathlib import Path
from typing import Any

from ...domain.cache import apply_page, empty_cache
from ...domain.identity import Scope


class SqliteStore:
    """每次操作独立连接，消息与游标在同一 SQLite 事务提交；无全局事件循环。"""

    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path == ":memory:":
            raise ValueError("a persistent database path is required")
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)

    def _run(
        self,
        scope: Scope,
        page: dict[str, Any] | None = None,
        reset: bool = False,
        clear: bool = False,
    ):
        with sqlite3.connect(self.path, timeout=15) as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                "CREATE TABLE IF NOT EXISTS cache(scope TEXT PRIMARY KEY,data TEXT NOT NULL)"
            )
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT data FROM cache WHERE scope=?", (scope.key,)
            ).fetchone()
            current = json.loads(row[0]) if row else empty_cache()
            if clear:
                connection.execute("DELETE FROM cache WHERE scope=?", (scope.key,))
                return empty_cache()
            if page is not None:
                current = apply_page(current, page, reset)
                connection.execute(
                    "INSERT INTO cache(scope,data) VALUES(?,?) "
                    "ON CONFLICT(scope) DO UPDATE SET data=excluded.data",
                    (scope.key, json.dumps(current)),
                )
            return current

    async def load(self, scope: Scope) -> dict[str, Any]:
        return await asyncio.to_thread(self._run, scope)

    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]:
        return await asyncio.to_thread(self._run, scope, page, reset)

    async def clear(self, scope: Scope) -> None:
        await asyncio.to_thread(self._run, scope, None, False, True)
