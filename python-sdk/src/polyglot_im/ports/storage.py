from typing import Any, Protocol

from ..domain.identity import Scope


class LocalStore(Protocol):
    async def load(self, scope: Scope) -> dict[str, Any]: ...
    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]: ...
    async def clear(self, scope: Scope) -> None: ...
