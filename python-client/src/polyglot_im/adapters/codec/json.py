import json
from typing import Any


class JsonCodec:
    """将协议对象编码为 JSON，并拒绝非对象的解码结果。"""

    content_type = "application/json"

    def encode(self, value: dict[str, Any]) -> str:
        """将协议对象编码为传输格式，保留字符串序号与原始业务载荷。"""
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    def decode(self, value: str | bytes) -> dict[str, Any]:
        """解析传输正文；格式或协议结构不合法时返回或抛出解码错误。"""
        result = json.loads(value)
        if not isinstance(result, dict):
            raise ValueError("wire value must be an object")
        return result
