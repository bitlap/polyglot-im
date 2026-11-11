from typing import Any, Protocol

from ..domain.identity import Scope


class LocalStore(Protocol):
    """按账号作用域保存消息和同步水位；合并与游标更新必须原子提交。"""

    async def load(self, scope: Scope) -> dict[str, Any]:
        """读取指定账号作用域的缓存；尚无记录时返回空状态。"""
        ...

    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]:
        """原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。"""
        ...

    async def clear(self, scope: Scope) -> None:
        """只清除指定账号作用域的本地缓存，不删除服务器消息。"""
        ...
