from typing import Any, Protocol


class WireCodec(Protocol):
    """HTTP 正文和实时帧的编解码端口，同一连接链路应使用同一实现。"""

    content_type: str

    def encode(self, value: dict[str, Any]) -> str | bytes:
        """将协议对象编码为传输格式，保留字符串序号与原始业务载荷。"""
        ...

    def decode(self, value: str | bytes) -> dict[str, Any]:
        """解析传输正文；格式或协议结构不合法时返回或抛出解码错误。"""
        ...
