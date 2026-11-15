from typing import Protocol

from ..domain.outbox import OutboxClaim, OutboxCompletion


class Outbox(Protocol):
    """派发所需的持久化端口；实现负责原子领取、数据库时钟与租约围栏。"""

    async def claim(self, lease_seconds: int) -> OutboxClaim | None:
        """原子领取一条到期事件并提交正时长租约；无记录时返回 None。"""
        ...

    async def complete(self, row: OutboxClaim, result: OutboxCompletion) -> bool:
        """按事件 ID 与原租约原子确认；已被重新领取时返回 false，不改动记录。"""
        ...
