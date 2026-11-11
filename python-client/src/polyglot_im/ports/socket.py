from collections.abc import AsyncIterator
from contextlib import AbstractAsyncContextManager
from typing import Protocol


class SocketSession(Protocol):
    """已建立的实时会话，提供入站帧和确认发送；确认前必须完成本地提交。"""

    def __aiter__(self) -> AsyncIterator[str | bytes]:
        """逐帧异步读取实时会话内容，结束或失败时将控制权交还同步层。"""
        ...

    async def send(self, value: str | bytes) -> None:
        """向当前实时连接写入编码帧；连接或写入失败交给调用方处理。"""
        ...


class SocketConnector(Protocol):
    """创建实时会话的端口；调用方负责结束会话和释放连接。"""

    def connect(self, url: str) -> AbstractAsyncContextManager[SocketSession]:
        """建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。"""
        ...
