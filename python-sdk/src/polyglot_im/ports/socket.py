from collections.abc import AsyncIterator
from contextlib import AbstractAsyncContextManager
from typing import Protocol


class SocketSession(Protocol):
    def __aiter__(self) -> AsyncIterator[str | bytes]: ...
    async def send(self, value: str | bytes) -> None: ...


class SocketConnector(Protocol):
    def connect(self, url: str) -> AbstractAsyncContextManager[SocketSession]: ...
