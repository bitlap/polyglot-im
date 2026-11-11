import asyncio
import random
from collections.abc import Awaitable, Callable
from urllib.parse import quote

from ..adapters.codec.json import JsonCodec
from ..common.errors import ImError
from ..config.protocol import API_PREFIX
from ..config.retry import RetryPolicy
from ..domain.identity import Scope
from ..ports.codec import WireCodec
from ..ports.transport import HttpRequest, HttpTransport


class ApiClient:
    """调用 IM HTTP API，统一处理身份绑定、响应信封、认证刷新和有界重试。"""

    def __init__(
        self,
        transport: HttpTransport,
        refresh_auth: Callable[[], Awaitable[None]] | None = None,
        expected_scope: Scope | None = None,
        *,
        codec: WireCodec | None = None,
        retry_policy: RetryPolicy | None = None,
    ):
        """借用传输并保存认证刷新、预期账号及重试参数；此构造不发送请求。"""
        self.codec = codec if codec is not None else JsonCodec()
        self.retry_policy = retry_policy if retry_policy is not None else RetryPolicy()
        self.transport, self.refresh_auth = transport, refresh_auth
        self._refresh_lock = asyncio.Lock()
        self.expected_scope = expected_scope
        self._auth_generation = 0

    def scoped(self, scope: Scope):
        """创建绑定指定账号作用域的请求入口，保留注入的传输和编解码器。"""
        return ApiClient(
            self.transport,
            self.refresh_auth,
            scope,
            codec=self.codec,
            retry_policy=self.retry_policy,
        )

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        """请求相对 IM API 路径并返回解包后的对象；临时错误有限重试，认证刷新最多一次。"""
        refreshed = False
        generation = self._auth_generation
        attempt = 0
        while True:
            try:
                response = await self.transport.execute(
                    HttpRequest(
                        method,
                        API_PREFIX + path,
                        headers={
                            "X-IM-App-ID": quote(self.expected_scope.app_id, safe=""),
                            "X-IM-User-ID": quote(self.expected_scope.user_id, safe=""),
                        }
                        if self.expected_scope
                        else {},
                        body=body,
                    )
                )
            except (OSError, TimeoutError):
                if attempt >= self.retry_policy.max_retries:
                    raise
                await asyncio.sleep(
                    self.retry_policy.delay(attempt) + random.random() * self.retry_policy.jitter
                )
                attempt += 1
                continue
            try:
                data = self.codec.decode(response.body)
            except (ValueError, UnicodeDecodeError):
                if 200 <= response.status < 300:
                    raise
                data = {}
            if 200 <= response.status < 300:
                if isinstance(data.get("code"), int):
                    if data["code"] != 200 or not isinstance(data.get("data"), dict):
                        raise ValueError("invalid API response envelope")
                    return data["data"]
                return data
            if response.status == 401 and self.refresh_auth and not refreshed:
                async with self._refresh_lock:
                    if generation == self._auth_generation:
                        await self.refresh_auth()
                        self._auth_generation += 1
                refreshed = True
                continue
            if (
                response.status in {429, 500, 502, 503, 504}
                and attempt < self.retry_policy.max_retries
            ):
                delay = response.headers.get("retry-after", "0")
                wait = float(delay) if delay.isdigit() else 0
                await asyncio.sleep(
                    max(
                        min(wait, self.retry_policy.retry_after_limit),
                        self.retry_policy.delay(attempt),
                    )
                    + random.random() * self.retry_policy.jitter
                )
                attempt += 1
                continue
            raise ImError(
                response.status,
                data.get("error_code")
                or (data.get("code") if isinstance(data.get("code"), str) else "HTTP_ERROR"),
                data.get("message", "request failed"),
            )
