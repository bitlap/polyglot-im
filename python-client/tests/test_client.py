import asyncio

from polyglot_im.client import Scope, SqliteStore, apply_page, empty_cache


def test_page_monotonic_and_duplicate():
    page = {
        "event_position": "2",
        "cursor": "opaque",
        "latest_seq": "0",
        "read_seq": "0",
        "unread_count": "0",
    }
    cache = apply_page(empty_cache(), page)
    assert apply_page(cache, {"event_position": "1", "cursor": "old"}) == cache
    assert apply_page(cache, page) == cache


def test_sqlite_restart_and_account_isolation(tmp_path):
    asyncio.run(_sqlite_restart(tmp_path))


async def _sqlite_restart(tmp_path):
    path = tmp_path / "im.db"
    alice = Scope("https://im.example", "app", "alice")
    bob = Scope("https://im.example", "app", "bob")
    await SqliteStore(path).commit(alice, {"cursor": "opaque", "event_position": "3"})
    assert (await SqliteStore(path).load(alice))["cursor"] == "opaque"
    assert (await SqliteStore(path).load(bob))["cursor"] is None
    await SqliteStore(path).clear(alice)
    assert (await SqliteStore(path).load(alice))["cursor"] is None


def test_shared_sync_contract_and_failed_commit(tmp_path):
    asyncio.run(_shared_sync(tmp_path))


async def _shared_sync(tmp_path):
    import json
    from pathlib import Path

    page = json.loads(
        (Path(__file__).resolve().parents[2] / "protocol/fixtures/sync.json").read_text()
    )
    store = SqliteStore(tmp_path / "contract.db")
    scope = Scope("https://im.example", "app", "alice")
    state = await store.commit(scope, page)
    assert state["latest_seq"] == "9007199254740993"
    assert state["unread_count"] == "1"
    try:
        await store.commit(scope, {"cursor": "must-not-commit", "messages": [{}]})
    except (ValueError, KeyError):
        pass
    else:
        raise AssertionError("invalid message accepted")
    assert (await store.load(scope)) == state


def test_gateway_html_is_retried_but_permission_error_is_not():
    asyncio.run(_gateway_retry())


async def _gateway_retry():
    from polyglot_im import ApiClient, HttpResponse, ImError

    class Transport:
        calls = 0

        async def execute(self, request):
            self.calls += 1
            if self.calls == 1:
                return HttpResponse(503, b"<html>gateway unavailable</html>")
            return HttpResponse(200, b'{"ok":true}')

    transport = Transport()
    assert await ApiClient(transport).request("GET", "/state") == {"ok": True}
    assert transport.calls == 2

    class Forbidden:
        async def execute(self, request):
            return HttpResponse(403, b"forbidden")

    try:
        await ApiClient(Forbidden()).request("GET", "/state")
    except ImError as error:
        assert error.status == 403
    else:
        raise AssertionError("forbidden was ignored")


def test_sqlite_connections_close_after_success_and_rollback(tmp_path, monkeypatch):
    import sqlite3

    import pytest

    from polyglot_im.adapters.storage import sqlite as adapter

    connect = sqlite3.connect
    connections = []

    def tracked_connect(*args, **kwargs):
        kwargs["check_same_thread"] = False  # 测试结束后在主线程确认连接已关闭。
        connection = connect(*args, **kwargs)
        connections.append(connection)
        return connection

    monkeypatch.setattr(adapter.sqlite3, "connect", tracked_connect)

    async def run():
        scope = Scope("https://im.example", "app", "alice")
        store = SqliteStore(tmp_path / "closed.db")
        await store.load(scope)
        await store.commit(scope, {"cursor": "saved", "event_position": "1"})
        with pytest.raises(KeyError):
            await store.commit(scope, {"messages": [{}]})
        assert (await store.load(scope))["cursor"] == "saved"
        await store.clear(scope)

    asyncio.run(run())
    assert len(connections) == 5
    for connection in connections:
        with pytest.raises(sqlite3.ProgrammingError, match="closed"):
            connection.execute("SELECT 1")


def test_read_watermark_covers_cache_and_late_history_not_new_messages():
    import json
    from pathlib import Path

    page = json.loads(
        (Path(__file__).resolve().parents[2] / "protocol/fixtures/sync.json").read_text()
    )
    message = page["events"][0]["data"]
    state = apply_page(empty_cache(), page)
    state = apply_page(state, {"read_seq": message["conversation_seq"], "unread_count": "0"})
    assert state["messages"][message["message_id"]]["is_read"]
    state = apply_page(
        state,
        {
            "messages": [
                message,
                {
                    **message,
                    "message_id": "older",
                    "conversation_seq": "9007199254740992",
                    "is_read": False,
                },
                {
                    **message,
                    "message_id": "newer",
                    "conversation_seq": "9007199254740994",
                    "is_read": False,
                },
            ],
            "unread_count": "1",
        },
    )
    assert state["messages"][message["message_id"]]["is_read"]
    assert state["messages"]["older"]["is_read"]
    assert not state["messages"]["newer"].get("is_read", False)
    assert state["unread_count"] == "1"
