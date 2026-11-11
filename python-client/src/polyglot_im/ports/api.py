from typing import Protocol

from ..domain.identity import Scope


class RemoteApi(Protocol):
    """IM 业务请求端口；路径相对统一 API 前缀，身份绑定不能作为认证凭据。"""

    def scoped(self, scope: Scope) -> "RemoteApi":
        """创建绑定指定账号作用域的请求入口，保留注入的传输和编解码器。"""
        ...

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        """执行相对 IM API 的业务请求并返回解包对象；业务或传输失败向调用方传递。"""
        ...
