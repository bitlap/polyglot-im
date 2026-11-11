"""异步传输端口与认证装饰器；核心不依赖具体网络库。"""

from dataclasses import dataclass, field
from typing import Any, Protocol
from urllib.parse import urlsplit


@dataclass(frozen=True)
class HttpRequest:
    method: str
    path: str
    headers: dict[str, str] = field(default_factory=dict)
    body: dict[str, Any] | None = None

    def __post_init__(self) -> None:
        uri = urlsplit(self.path)
        if (
            not self.path.startswith("/")
            or self.path.startswith("//")
            or uri.scheme
            or uri.netloc
            or uri.fragment
            or "\\" in self.path
            or any(c in self.path for c in "\r\n")
        ):
            raise ValueError("path must be relative to the configured origin")


@dataclass(frozen=True)
class HttpResponse:
    status: int
    body: bytes
    headers: dict[str, str] = field(default_factory=dict)


class HttpTransport(Protocol):
    async def execute(self, request: HttpRequest) -> HttpResponse: ...


class TokenProvider(Protocol):
    async def token(self) -> str: ...
