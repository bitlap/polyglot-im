from typing import Protocol

from ..domain.message import Message


class NotificationPublisher(Protocol):
    """业务通知发布端口；接受成功表示 IM 已提交，不表示用户已读。"""

    async def send(
        self, recipient_id: str, client_message_id: str, type: str, payload: dict
    ) -> Message:
        """使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。"""
        ...
