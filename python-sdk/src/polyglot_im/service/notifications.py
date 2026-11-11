from ..domain.message import Message
from ..ports.api import RemoteApi


class NotificationSender:
    def __init__(self, api: RemoteApi):
        self.api = api

    async def send(
        self, recipient_id: str, client_message_id: str, type: str, payload: dict
    ) -> Message:
        if not client_message_id:
            raise ValueError("stable client_message_id required")
        result = await self.api.request(
            "POST",
            "/notifications",
            {
                "recipient_id": recipient_id,
                "client_message_id": client_message_id,
                "type": type,
                "payload": payload,
            },
        )
        return Message.from_dict(result)
