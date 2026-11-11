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
