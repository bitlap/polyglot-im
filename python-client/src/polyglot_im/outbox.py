"""业务 Outbox 的持久化、领域记录与派发公共入口。"""

from .adapters.store.postgres_outbox import PostgresOutbox, open_postgres_outbox
from .domain.outbox import NotificationIntent, OutboxClaim, OutboxCompletion, OutboxState
from .ports.outbox import Outbox
from .ports.transactional_outbox import TransactionalOutbox
from .service.outbox_dispatcher import OutboxDispatcher

__all__ = [
    "PostgresOutbox",
    "open_postgres_outbox",
    "NotificationIntent",
    "OutboxClaim",
    "OutboxCompletion",
    "OutboxState",
    "Outbox",
    "TransactionalOutbox",
    "OutboxDispatcher",
]
