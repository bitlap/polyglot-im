import asyncio
import json

import pytest

from polyglot_im import ApiClient, HttpResponse, ImClient, ImError, Scope, SqliteStore


class Transport:
    def __init__(self, callback):
        self.callback = callback
        self.requests = []

    async def execute(self, request):
        self.requests.append(request)
        return await self.callback(request)


def test_identity_mismatch_leaves_cache_untouched(tmp_path):
    async def run():
        scope = Scope("https://im.example", "app", "alice")
        store = SqliteStore(tmp_path / "im.db")
        await store.commit(scope, {"cursor": "old", "event_position": "1"})

        async def reply(request):
            return HttpResponse(200, b'{"app_id":"app","user_id":"bob"}')

        transport = Transport(reply)
        client = ImClient(ApiClient(transport), scope.environment, scope, "ios", store)
        with pytest.raises(ImError, match="credential"):
            await client.initialize()
        assert (await store.load(scope))["cursor"] == "old"
        assert len(transport.requests) == 1

    asyncio.run(run())


def test_expired_cursor_rebuilds_snapshot_atomically(tmp_path):
    async def run():
        scope = Scope("https://im.example", "app", "alice")
        store = SqliteStore(tmp_path / "im.db")
        await store.commit(scope, {"cursor": "old", "event_position": "1"})

        async def reply(request):
            if request.path.endswith("/session"):
                return HttpResponse(200, b'{"app_id":"app","user_id":"alice"}')
            if "/sync?" in request.path:
                return HttpResponse(410, b'{"code":"RESET_REQUIRED"}')
            return HttpResponse(
                200,
                json.dumps(
                    {
                        "cursor": "fresh",
                        "event_position": "3",
                        "messages": [],
                        "latest_seq": "0",
                        "read_seq": "0",
                        "unread_count": "0",
                    }
                ).encode(),
            )

        client = ImClient(ApiClient(Transport(reply)), scope.environment, scope, "ios", store)
        await client.initialize()
        assert client.cache["cursor"] == "fresh"
        assert (await store.load(scope))["cursor"] == "fresh"

    asyncio.run(run())


def test_concurrent_401_refresh_is_coalesced_and_only_attempted_once():
    async def run():
        arrived = 0
        all_arrived = asyncio.Event()
        refreshed = False
        refreshes = 0

        async def refresh():
            nonlocal refreshed, refreshes
            refreshes += 1
            refreshed = True

        async def reply(request):
            nonlocal arrived
            if not refreshed:
                arrived += 1
                if arrived == 8:
                    all_arrived.set()
                await all_arrived.wait()
                return HttpResponse(401, b"{}")
            return HttpResponse(200, b'{"ok":true}')

        api = ApiClient(Transport(reply), refresh)
        results = await asyncio.wait_for(
            asyncio.gather(*(api.request("GET", "/state") for _ in range(8))), 5
        )
        assert refreshes == 1
        assert all(row["ok"] for row in results)

        async def always_expired(request):
            return HttpResponse(401, b"{}")

        with pytest.raises(ImError):
            await ApiClient(Transport(always_expired), refresh).request("GET", "/state")
        assert refreshes == 2

    asyncio.run(run())


def test_closed_client_cannot_read_or_mutate_server(tmp_path):
    async def run():
        async def reply(request):
            raise AssertionError("closed client must not access network")

        scope = Scope("https://im.example", "app", "alice")
        transport = Transport(reply)
        client = ImClient(
            ApiClient(transport), scope.environment, scope, "ios", SqliteStore(tmp_path / "im.db")
        )
        await client.close(logout=False)
        for operation in [
            client.sync(),
            client.history(),
            client.mark_read(),
            client.set_push(None),
        ]:
            with pytest.raises(RuntimeError, match="closed"):
                await operation
        assert not transport.requests

    asyncio.run(run())


def test_device_ids_are_single_encoded_path_segments(tmp_path):
    async def run():
        async def reply(request):
            return HttpResponse(200, b'{"ok":true}')

        scope = Scope("https://im.example", "app", "alice")
        transport = Transport(reply)
        client = ImClient(
            ApiClient(transport),
            scope.environment,
            scope,
            "手机 /?#%+",
            SqliteStore(tmp_path / "im.db"),
        )
        await client.set_push(None)
        await client.close()
        prefix = "/api/v1/im/devices/%E6%89%8B%E6%9C%BA%20%2F%3F%23%25%2B/"
        assert [r.path for r in transport.requests] == [prefix + "push-token", prefix + "session"]

    asyncio.run(run())


def test_sync_storage_failure_does_not_leave_state_syncing(tmp_path):
    async def run():
        async def reply(request):
            return HttpResponse(200, b'{"cursor":"new","event_position":"1"}')

        class FailingStore(SqliteStore):
            async def commit(self, scope, page, reset=False):
                raise OSError("disk full")

        scope = Scope("https://im.example", "app", "alice")
        client = ImClient(
            ApiClient(Transport(reply)),
            scope.environment,
            scope,
            "ios",
            FailingStore(tmp_path / "im.db"),
        )
        with pytest.raises(OSError, match="disk full"):
            await client.sync()
        assert client.connection_state == "retrying"
        assert client.cache["cursor"] is None
        await client.close(logout=False)

    asyncio.run(run())


def test_close_during_sync_does_not_publish_late_state(tmp_path):
    async def run():
        started, released = asyncio.Event(), asyncio.Event()

        async def reply(request):
            started.set()
            await released.wait()
            return HttpResponse(200, b'{"cursor":"late","event_position":"1"}')

        scope = Scope("https://im.example", "app", "alice")
        store = SqliteStore(tmp_path / "im.db")
        client = ImClient(ApiClient(Transport(reply)), scope.environment, scope, "ios", store)
        pending = asyncio.create_task(client.sync())
        await started.wait()
        await client.close(logout=False)
        released.set()
        with pytest.raises(RuntimeError, match="closed"):
            await pending
        assert client.connection_state == "idle"
        assert (await store.load(scope))["cursor"] is None

    asyncio.run(run())
