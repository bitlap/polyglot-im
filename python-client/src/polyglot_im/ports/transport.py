"""异步传输端口与认证装饰器；核心不依赖具体网络库。"""

from dataclasses import dataclass, field
from typing import Any, Protocol
from urllib.parse import urlsplit


@dataclass(frozen=True)
class HttpRequest:
    """相对配置源的 HTTP 请求；拒绝绝对 URL、跨源路径和非法控制字符。"""

    method: str
    path: str
    headers: dict[str, str] = field(default_factory=dict)
    body: dict[str, Any] | None = None

    def __post_init__(self) -> None:
        """在数据模型构造后校验字段约束，非法输入不进入消息或请求流程。"""
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
    """传输层 HTTP 响应，保留真实状态码、响应正文和响应头。"""

    status: int
    body: bytes
    headers: dict[str, str] = field(default_factory=dict)


class HttpTransport(Protocol):
    """执行单次 HTTP 传输的端口；认证、业务重试与响应解释由上层负责。"""

    async def execute(self, request: HttpRequest) -> HttpResponse:
        """执行一次传输并保留真实 HTTP 状态；不在此层推断消息送达或已读。"""
        ...


class TokenProvider(Protocol):
    """每次请求读取有效登录凭证的异步端口。"""

    async def token(self) -> str:
        """读取本次请求使用的登录令牌，不将令牌持久化到消息缓存。"""
        ...
