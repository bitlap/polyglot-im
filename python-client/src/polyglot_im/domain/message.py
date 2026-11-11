"""消息 v1 编解码，保留未知消息类型与 payload。"""

import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any


@dataclass(frozen=True)
class Message:
    """协议 v1 消息；序号保留为字符串，未知消息类型和载荷原样保存。"""

    schema_version: int
    message_id: str
    conversation_id: str
    conversation_seq: str
    type: str
    payload: dict[str, Any]
    created_at: str

    def __post_init__(self) -> None:
        """在数据模型构造后校验字段约束，非法输入不进入消息或请求流程。"""
        if type(self.schema_version) is not int or self.schema_version != 1:
            raise ValueError("unsupported schema_version")
        if any(
            not isinstance(v, str) or not v
            for v in (self.message_id, self.conversation_id, self.type)
        ):
            raise ValueError("empty identity or type")
        if not isinstance(self.conversation_seq, str) or not re.fullmatch(
            r"[1-9][0-9]*", self.conversation_seq
        ):
            raise ValueError("invalid conversation_seq")
        if not isinstance(self.payload, dict):
            raise ValueError("payload must be an object")
        if not isinstance(self.created_at, str) or not self.created_at.endswith("Z"):
            raise ValueError("created_at must be UTC")
        datetime.fromisoformat(self.created_at.replace("Z", "+00:00"))

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Message":
        """从协议字段创建消息，缺失字段或非法序号、时间会拒绝构造。"""
        return cls(**{name: data[name] for name in cls.__dataclass_fields__})

    def to_dict(self) -> dict[str, Any]:
        """导出当前消息的协议字段，保留字符串序号和未知类型载荷。"""
        return {name: getattr(self, name) for name in self.__dataclass_fields__}
