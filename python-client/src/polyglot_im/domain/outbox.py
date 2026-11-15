"""业务通知 Outbox 的不可变领取及确认记录，不依赖数据库驱动。"""

from dataclasses import dataclass
from enum import StrEnum
from typing import Any


@dataclass(frozen=True)
class NotificationIntent:
    """稳定业务事件键与接收者、类型、载荷；重放不得改变事件内容。"""

    event_id: str
    recipient: str
    kind: str
    payload: dict[str, Any]


@dataclass(frozen=True)
class OutboxClaim:
    """已领取的通知意图、已完成尝试次数与围栏租约；确认必须携带原租约。"""

    event: NotificationIntent
    attempts: int
    lease: str


class OutboxState(StrEnum):
    """业务 Outbox 的现有持久化状态值。"""

    PENDING = "pending"
    SENT = "sent"
    DEAD = "dead"


@dataclass(frozen=True)
class OutboxCompletion:
    """派发结果与下次领取延迟；存储使用数据库时钟，错误只保存稳定错误码。"""

    state: OutboxState
    retry_delay_seconds: int
    error: str | None = None

    def __post_init__(self):
        """拒绝负数或非整数延迟，避免非法调度值进入持久化。"""
        if (
            isinstance(self.retry_delay_seconds, bool)
            or not isinstance(self.retry_delay_seconds, int)
            or self.retry_delay_seconds < 0
        ):
            raise ValueError("retry_delay_seconds must be a non-negative integer")
