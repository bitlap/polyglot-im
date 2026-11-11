"""HTTPX 异步适配器；通过 polyglot-im-python-sdk[http] 安装可选依赖。"""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx

from ..ports.codec import WireCodec
from ..ports.transport import HttpRequest, HttpResponse
from .codec.json import JsonCodec


class HttpxTransport:
    """借用已创建的 AsyncClient，不负责关闭宿主连接池。"""

    def __init__(self, client: httpx.AsyncClient, codec: WireCodec | None = None) -> None:
        if client.base_url.scheme not in {"http", "https"} or not client.base_url.host:
            raise ValueError("AsyncClient must have an absolute HTTP(S) base_url")
        self._client = client
        self.codec = codec if codec is not None else JsonCodec()

    async def execute(self, request: HttpRequest) -> HttpResponse:
        try:
            response = await self._client.request(
                request.method,
                request.path,
                headers={"Content-Type": self.codec.content_type, **request.headers},
                content=self.codec.encode(request.body) if request.body is not None else None,
                follow_redirects=False,
            )
        except httpx.TransportError as error:
            raise OSError("HTTP transport failed") from error
        return HttpResponse(response.status_code, response.content, dict(response.headers))


@asynccontextmanager
async def open_httpx_transport(
    base_url: str,
    *,
    timeout: float = 10.0,
    trust_env: bool = False,
    network: httpx.AsyncBaseTransport | None = None,
    codec: WireCodec | None = None,
) -> AsyncIterator[HttpxTransport]:
    """拥有连接池的工厂；异常与取消退出时也执行关闭，支持注入网络后端。"""
    async with httpx.AsyncClient(
        base_url=base_url,
        timeout=timeout,
        trust_env=trust_env,
        transport=network,
        follow_redirects=False,
    ) as client:
        yield HttpxTransport(client, codec)
