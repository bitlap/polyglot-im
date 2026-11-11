"""上层可以替换网络、帧协议和原子存储，且不改变落盘后 ack 的语义。"""

import asyncio
from contextlib import asynccontextmanager

import httpx

from polyglot_im import ApiClient, ImClient, JsonCodec, RetryPolicy, Scope
from polyglot_im.adapters.httpx import open_httpx_transport
from polyglot_im.domain.cache import apply_page, empty_cache


class PrefixCodec(JsonCodec):
    content_type = "application/x-test-frame"

    def encode(self, value):
        return "frame:" + super().encode(value)

    def decode(self, value):
        text = value.decode() if isinstance(value, bytes) else value
        assert text.startswith("frame:")
        return super().decode(text[6:])


def test_http_codec_is_shared_by_adapter_and_api_and_survives_scoping():
    async def run():
        codec = PrefixCodec()

        async def handle(request):
            assert request.headers["content-type"] == codec.content_type
            assert codec.decode(request.content) == {"up_to_seq": "9"}
            return httpx.Response(200, content=codec.encode({"code": 200, "data": {"ok": True}}))

        async with open_httpx_transport(
            "https://im.example", codec=codec, network=httpx.MockTransport(handle)
        ) as transport:
            api = ApiClient(transport, codec=codec, retry_policy=RetryPolicy(max_retries=0))
            scoped = api.scoped(Scope("https://im.example", "app", "alice"))
            assert scoped.codec is codec
            assert scoped.retry_policy is api.retry_policy
            assert await scoped.request("PUT", "/read", {"up_to_seq": "9"}) == {"ok": True}

    asyncio.run(run())


def test_custom_socket_and_store_ack_only_after_commit_and_release_session():
    async def run():
        scope = Scope("https://im.example", "app", "alice")
        codec = PrefixCodec()
        closed = []

        class Store:
            def __init__(self):
                self.value = empty_cache()

            async def load(self, _scope):
                return self.value

            async def commit(self, _scope, page, reset=False):
                self.value = apply_page(self.value, page, reset)
                return self.value

            async def clear(self, _scope):
                self.value = empty_cache()

        store = Store()

        class Api:
            def scoped(self, _scope):
                return self

            async def request(self, method, path, body=None):
                if path == "/session":
                    return {"app_id": "app", "user_id": "alice", "ticket": "test-ticket"}
                return {"cursor": "c1", "event_position": "1", "events": [], "has_more": False}

        class Session:
            def __aiter__(self):
                return self.frames()

            async def frames(self):
                yield codec.encode({"data": {"cursor": "c2", "event_position": "2", "events": []}})
                await asyncio.Future()

            async def send(self, raw):
                assert store.value["event_position"] == "2"
                assert codec.decode(raw) == {"cursor": "c2"}

        class Connector:
            @asynccontextmanager
            async def connect(self, uri):
                assert uri.startswith("wss://im.example/api/v1/im/ws?")
                try:
                    yield Session()
                finally:
                    closed.append(True)

        client = ImClient(
            Api(),
            scope.environment,
            scope,
            "ios",
            store,
            socket_connector=Connector(),
            codec=codec,
        )
        iterator = client.watch()
        result = await anext(iterator)
        assert result["event_position"] == "2"
        await iterator.aclose()
        assert closed == [True]
        await client.close(logout=False)

    asyncio.run(run())
