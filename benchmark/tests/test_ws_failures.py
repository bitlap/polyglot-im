"""验证 WS 超时仍保存计数，连接建立失败不能冒充消息零错误。"""

import asyncio

import httpx

from benchmark import run


def test_ws_setup_failure_is_preserved_without_discarding_round():
    async def exercise():
        config = {
            "base_url": "http://a",
            "service_tokens": ["p"],
            "users": [{"id": "u", "token": "t"}],
        }
        async with httpx.AsyncClient(
            transport=httpx.MockTransport(lambda _: httpx.Response(503))
        ) as http:
            row = await run.ws_trial(http, config, 1, 1)
        assert row["attempted"] == 0
        assert row["connection_errors"] == {"HTTPStatusError": 1}
        assert row["client_cpu_seconds"] >= 0

    asyncio.run(exercise())


def test_ws_delivery_timeout_retains_attempts_and_failure_count(monkeypatch):
    class Socket:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        def __aiter__(self):
            return self

        async def __anext__(self):
            raise StopAsyncIteration

    monkeypatch.setattr(run, "connect", lambda *args, **kwargs: Socket())
    real_wait_for = asyncio.wait_for

    async def short_wait_for(awaitable, timeout):
        return await real_wait_for(awaitable, 0.02 if timeout == 30 else timeout)

    monkeypatch.setattr(run.asyncio, "wait_for", short_wait_for)

    def respond(request):
        if request.url.path.endswith("/session"):
            return httpx.Response(200, json={"data": {"ticket": "t"}})
        if request.url.path.endswith("/state"):
            return httpx.Response(200, json={"data": {"cursor": "0"}})
        return httpx.Response(201, json={"data": {"message_id": "m"}})

    async def exercise():
        config = {
            "base_url": "http://a",
            "service_tokens": ["p"],
            "users": [{"id": "u", "token": "t"}],
        }
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            row = await run.ws_trial(http, config, 1, 1)
        assert row["attempted"] == row["failed"] == 1
        assert row["latencies_ms"] == []
        assert row["connection_errors"] == {"TimeoutError": 1}
        assert row["errors"] == {"WS_DELIVERY_UNCONFIRMED": 1}

    asyncio.run(exercise())
