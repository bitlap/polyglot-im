from dataclasses import replace

from ..ports.transport import HttpRequest, HttpResponse, HttpTransport, TokenProvider


class AuthenticatedTransport:
    """借用底层传输；其创建者负责关闭连接池，不自动重试或吞掉取消。"""

    def __init__(self, underlying: HttpTransport, tokens: TokenProvider) -> None:
        self._underlying = underlying
        self._tokens = tokens

    async def execute(self, request: HttpRequest) -> HttpResponse:
        token = await self._tokens.token()
        if not token.strip() or any(c in token for c in "\r\n"):
            raise ValueError("invalid bearer token")
        headers = {k: v for k, v in request.headers.items() if k.lower() != "authorization"}
        headers["Authorization"] = f"Bearer {token}"
        return await self._underlying.execute(replace(request, headers=headers))
