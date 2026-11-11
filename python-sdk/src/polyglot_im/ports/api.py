from typing import Protocol

from ..domain.identity import Scope


class RemoteApi(Protocol):
    def scoped(self, scope: Scope) -> "RemoteApi": ...
    async def request(self, method: str, path: str, body: dict | None = None) -> dict: ...
