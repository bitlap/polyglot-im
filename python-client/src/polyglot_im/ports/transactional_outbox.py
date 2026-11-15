from typing import Protocol, TypeVar

from .outbox import Outbox

Transaction = TypeVar("Transaction", contravariant=True)


class TransactionalOutbox(Outbox, Protocol[Transaction]):
    """业务事务入队端口；事务类型由宿主及具体适配器约定，不引入驱动依赖。"""

    async def enqueue(
        self, connection: Transaction, event_id: str, recipient: str, kind: str, payload: dict
    ) -> None:
        """在调用方已开启的事务中写入幂等意图；不提交、回滚或关闭事务。"""
        ...
