import asyncio
import json
import sqlite3
from pathlib import Path

import pytest

from polyglot_im import Scope, SqliteStore

SCOPE = Scope("https://im.example", "app", "alice")
FIXTURES = Path(__file__).resolve().parents[2] / "protocol/fixtures"


def message(index):
    value = json.loads((FIXTURES / "message.json").read_text())
    value.update(
        message_id=f"message-{index}",
        conversation_seq=str(index),
        type="custom.future",
        payload={"text": "你好 👨‍👩‍👧‍👦", "future": "kept"},
    )
    return value


@pytest.mark.parametrize(
    "path", ["", ":memory:", "file::memory:", "file:cache?mode=memory&cache=shared"]
)
def test_requires_persistent_file(path):
    with pytest.raises(ValueError, match="persistent"):
        SqliteStore(path)


def test_parallel_instances_do_not_lose_updates(tmp_path):
    async def run():
        path = tmp_path / "账户 缓存.sqlite"
        await asyncio.gather(
            *(
                SqliteStore(path).commit(SCOPE, {"messages": [message(i)], "latest_seq": str(i)})
                for i in range(1, 17)
            )
        )
        state = await SqliteStore(path).load(SCOPE)
        assert len(state["messages"]) == 16
        assert state["latest_seq"] == "16"
        assert state["messages"]["message-1"]["payload"]["future"] == "kept"

    asyncio.run(run())


def test_real_sqlite_failure_rolls_back_cursor_and_releases_connection(tmp_path):
    async def run():
        path = tmp_path / "state.db"
        store = SqliteStore(path)
        saved = await store.commit(SCOPE, {"messages": [message(1)], "cursor": "saved"})
        with sqlite3.connect(path) as connection:
            connection.execute(
                "CREATE TRIGGER reject_cache BEFORE UPDATE ON cache "
                "BEGIN SELECT RAISE(ABORT,'test write failure'); END"
            )
        with pytest.raises(sqlite3.IntegrityError):
            await store.commit(SCOPE, {"messages": [message(2)], "cursor": "failed"})
        assert await SqliteStore(path).load(SCOPE) == saved
        with sqlite3.connect(path) as connection:
            connection.execute("DROP TRIGGER reject_cache")
        retried = await store.commit(SCOPE, {"cursor": "retry"})
        assert retried["cursor"] == "retry"
        assert len(retried["messages"]) == 1

    asyncio.run(run())


def test_scope_isolation_reset_and_clear_corrupt_state(tmp_path):
    async def run():
        path = tmp_path / "state.db"
        store = SqliteStore(path)
        others = [
            Scope("https://im.example", "app", "bob"),
            Scope("https://im.example", "other", "alice"),
            Scope("https://other.example", "app", "alice"),
        ]
        for scope in [SCOPE, *others]:
            await store.commit(scope, {"messages": [message(1)], "cursor": scope.key})
        reset = await store.commit(SCOPE, {"messages": [message(2)]}, reset=True)
        assert set(reset["messages"]) == {"message-2"}
        assert reset["cursor"] is None
        with sqlite3.connect(path) as connection:
            connection.execute("UPDATE cache SET data=? WHERE scope=?", ("{broken", SCOPE.key))
        with pytest.raises(json.JSONDecodeError):
            await store.load(SCOPE)
        await store.clear(SCOPE)
        assert not (await store.load(SCOPE))["messages"]
        for scope in others:
            assert (await store.load(scope))["cursor"] == scope.key

    asyncio.run(run())


def test_wal_read_does_not_wait_for_other_writer(tmp_path):
    async def run():
        path = tmp_path / "state.db"
        store = SqliteStore(path)
        saved = await store.commit(SCOPE, {"cursor": "saved"})
        with sqlite3.connect(path) as connection:
            assert connection.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
            connection.execute("BEGIN IMMEDIATE")
            value = await asyncio.wait_for(store.load(SCOPE), timeout=2)
            assert value == saved

    asyncio.run(run())
