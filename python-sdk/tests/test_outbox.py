import asyncio
import sqlite3

from polyglot_im.outbox import SqliteOutbox


def test_business_rollback_and_worker_restart(tmp_path):
    path = tmp_path / "business.db"
    outbox = SqliteOutbox(path)
    outbox.initialize()
    with sqlite3.connect(path) as connection:
        connection.execute("CREATE TABLE task(id TEXT PRIMARY KEY)")
    try:
        with sqlite3.connect(path) as connection:
            connection.execute("INSERT INTO task VALUES('rolled-back')")
            outbox.enqueue(connection, "rollback", "alice", "system.notice", {})
            raise RuntimeError("business transaction failed")
    except RuntimeError:
        pass
    with sqlite3.connect(path) as connection:
        assert connection.execute("SELECT count(*) FROM im_producer_outbox").fetchone()[0] == 0
        connection.execute("INSERT INTO task VALUES('committed')")
        outbox.enqueue(connection, "stable-key", "alice", "system.notice", {"text": "完成"})
    sent = []

    class Sender:
        async def send(self, user, key, kind, payload):
            sent.append((user, key, kind, payload))

    # 用新实例模拟重启；已提交业务意图保留，确认后不再重复领取。
    restarted = SqliteOutbox(path)
    assert asyncio.run(restarted.drain_once(Sender()))
    assert not asyncio.run(restarted.drain_once(Sender()))
    assert sent == [("alice", "stable-key", "system.notice", {"text": "完成"})]
