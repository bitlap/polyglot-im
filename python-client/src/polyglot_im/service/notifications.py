from ..domain.message import Message
from ..ports.api import RemoteApi


class NotificationSender:
    """以服务身份发布业务通知；同一业务事件的重试必须复用幂等键。"""

    def __init__(self, api: RemoteApi):
        """借用使用服务身份的 API 入口，不创建独立网络连接。"""
        self.api = api

    async def send(
        self, recipient_id: str, client_message_id: str, type: str, payload: dict
    ) -> Message:
        """使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。"""
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
