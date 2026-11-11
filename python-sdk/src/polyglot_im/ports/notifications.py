from typing import Protocol

from ..domain.message import Message


class NotificationPublisher(Protocol):
    async def send(
        self, recipient_id: str, client_message_id: str, type: str, payload: dict
    ) -> Message: ...
