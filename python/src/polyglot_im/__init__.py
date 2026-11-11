"""消息模型和可插拔异步传输；消息业务接口仍在后续里程碑实现。"""

from .message import Message
from .transport import (
    AuthenticatedTransport,
    HttpRequest,
    HttpResponse,
    HttpTransport,
    TokenProvider,
)

__all__ = [
    "Message",
    "HttpRequest",
    "HttpResponse",
    "HttpTransport",
    "TokenProvider",
    "AuthenticatedTransport",
]
