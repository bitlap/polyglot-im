"""业务 SQLite 事务 Outbox；enqueue 借用调用方事务，不自行提交业务数据。"""

from __future__ import annotations

import asyncio
import json
import sqlite3
import time
from pathlib import Path

from .client import ImError, NotificationSender


class SqliteOutbox:
    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path == ":memory:":
            raise ValueError("persistent business database required")

    def initialize(self) -> None:
        with sqlite3.connect(self.path) as connection:
            connection.execute(
                "CREATE TABLE IF NOT EXISTS im_producer_outbox("
                "event_id TEXT PRIMARY KEY,recipient TEXT NOT NULL,kind TEXT NOT NULL,"
                "payload TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',"
                "attempts INTEGER NOT NULL DEFAULT 0,next_at REAL NOT NULL DEFAULT 0,"
                "lease_until REAL NOT NULL DEFAULT 0,lease TEXT,error TEXT)"
            )

    @staticmethod
    def enqueue(
        connection: sqlite3.Connection, event_id: str, recipient: str, kind: str, payload: dict
    ) -> None:
        if not connection.in_transaction:
            raise ValueError("enqueue requires the caller's active business transaction")
        if not event_id:
            raise ValueError("stable event_id required")
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        previous = connection.execute(
            "SELECT recipient,kind,payload FROM im_producer_outbox WHERE event_id=?",
            (event_id,),
        ).fetchone()
        if previous:
            if previous != (recipient, kind, encoded):
                raise ValueError("event_id reused for different notification")
            return
        connection.execute(
            "INSERT INTO im_producer_outbox(event_id,recipient,kind,payload) VALUES(?,?,?,?)",
            (event_id, recipient, kind, encoded),
        )

    def _claim(self):
        import uuid

        now, lease = time.time(), str(uuid.uuid4())
        with sqlite3.connect(self.path, timeout=15) as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT event_id,recipient,kind,payload,attempts FROM im_producer_outbox "
                "WHERE state='pending' AND next_at<=? AND lease_until<=? "
                "ORDER BY rowid LIMIT 1",
                (now, now),
            ).fetchone()
            if row:
                connection.execute(
                    "UPDATE im_producer_outbox SET lease_until=?,lease=? WHERE event_id=?",
                    (now + 120, lease, row[0]),
                )
                return (*row, lease)
        return None

    def _finish(self, row, state: str, error: str | None):
        with sqlite3.connect(self.path, timeout=15) as connection:
            connection.execute(
                "UPDATE im_producer_outbox SET state=?,attempts=attempts+1,"
                "next_at=?,lease_until=0,lease=NULL,error=? WHERE event_id=? AND lease=?",
                (state, time.time() + min(300, 2 ** min(row[4], 9)), error, row[0], row[5]),
            )

    async def drain_once(self, sender: NotificationSender) -> bool:
        row = await asyncio.to_thread(self._claim)
        if row is None:
            return False
        try:
            await sender.send(row[1], row[0], row[2], json.loads(row[3]))
        except ImError as error:
            dead = error.status in {400, 403, 409, 413, 422}
            await asyncio.to_thread(self._finish, row, "dead" if dead else "pending", error.code)
        except (OSError, TimeoutError):
            await asyncio.to_thread(self._finish, row, "pending", "NETWORK_ERROR")
        else:
            await asyncio.to_thread(self._finish, row, "sent", None)
        # 取消/进程退出不确认，租约到期可重放；幂等键不变。
        return True
