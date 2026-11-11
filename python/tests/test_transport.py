import asyncio
import json

import httpx
import pytest

from polyglot_im import AuthenticatedTransport, HttpRequest
from polyglot_im.adapters.httpx import HttpxTransport, open_httpx_transport


class Tokens:
    value = "first"

    async def token(self):
        return self.value


def test_async_httpx_injection_and_token_replacement():
    async def run():
        seen = []

        async def handler(request):
            seen.append(request)
            return httpx.Response(200, json={"status": "alive"})

        tokens = Tokens()
        async with open_httpx_transport(
            "https://im.example.test", network=httpx.MockTransport(handler)
        ) as raw:
            transport = AuthenticatedTransport(raw, tokens)
            response = await transport.execute(
                HttpRequest("GET", "/health/live", {"authorization": "old"})
            )
            tokens.value = "second"
            await transport.execute(HttpRequest("GET", "/health/live"))
        assert json.loads(response.body) == {"status": "alive"}
        assert [r.headers["authorization"] for r in seen] == ["Bearer first", "Bearer second"]
        assert all(r.url.host == "im.example.test" for r in seen)

    asyncio.run(run())


@pytest.mark.parametrize("path", ["https://other.test/", "//other.test/", "relative", "/x#y"])
def test_reject_foreign_origin_and_fragment(path):
    with pytest.raises(ValueError):
        HttpRequest("GET", path)


def test_borrowed_client_is_not_closed_and_redirects_are_not_followed():
    async def run():
        async with httpx.AsyncClient(
            base_url="https://im.example.test",
            transport=httpx.MockTransport(
                lambda _: httpx.Response(302, headers={"location": "https://other.test"})
            ),
            follow_redirects=True,
        ) as client:
            transport = HttpxTransport(client)
            response = await transport.execute(HttpRequest("GET", "/health/live"))
            assert response.status == 302
            assert not client.is_closed
        assert client.is_closed

    asyncio.run(run())


def test_cancellation_releases_owned_network():
    async def run():
        started = asyncio.Event()
        closed = []

        class Network(httpx.AsyncBaseTransport):
            async def handle_async_request(self, request):
                started.set()
                await asyncio.Future()

            async def aclose(self):
                closed.append(True)

        async def request():
            async with open_httpx_transport("https://im.example.test", network=Network()) as raw:
                await AuthenticatedTransport(raw, Tokens()).execute(
                    HttpRequest("GET", "/health/live")
                )

        task = asyncio.create_task(request())
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert closed == [True]

    asyncio.run(run())


def test_empty_token_never_calls_network():
    async def run():
        class Raw:
            async def execute(self, request):
                raise AssertionError("network must not run")

        tokens = Tokens()
        tokens.value = " "
        with pytest.raises(ValueError, match="invalid bearer token"):
            await AuthenticatedTransport(Raw(), tokens).execute(HttpRequest("GET", "/health/live"))

    asyncio.run(run())
