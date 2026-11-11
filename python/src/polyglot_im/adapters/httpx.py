"""HTTPX 异步适配器；通过 polyglot-im-python[http] 安装可选依赖。"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx

from ..transport import HttpRequest, HttpResponse


class HttpxTransport:
    """借用已创建的 AsyncClient，不负责关闭宿主连接池。"""

    def __init__(self, client: httpx.AsyncClient) -> None:
        if client.base_url.scheme not in {"http", "https"} or not client.base_url.host:
            raise ValueError("AsyncClient must have an absolute HTTP(S) base_url")
        self._client = client

    async def execute(self, request: HttpRequest) -> HttpResponse:
        response = await self._client.request(
            request.method,
            request.path,
            headers=request.headers,
            json=request.body,
            follow_redirects=False,
        )
        return HttpResponse(response.status_code, response.content, dict(response.headers))


@asynccontextmanager
async def open_httpx_transport(
    base_url: str,
    *,
    timeout: float = 10.0,
    trust_env: bool = False,
    network: httpx.AsyncBaseTransport | None = None,
) -> AsyncIterator[HttpxTransport]:
    """拥有连接池的工厂；异常与取消退出时也执行关闭，支持注入网络后端。"""
    async with httpx.AsyncClient(
        base_url=base_url,
        timeout=timeout,
        trust_env=trust_env,
        transport=network,
        follow_redirects=False,
    ) as client:
        yield HttpxTransport(client)
