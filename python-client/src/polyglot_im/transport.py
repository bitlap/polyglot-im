"""源码兼容导出；端口与认证服务分层维护。"""

from .ports.transport import HttpRequest, HttpResponse, HttpTransport, TokenProvider
from .service.auth import AuthenticatedTransport

__all__ = [
    "HttpRequest",
    "HttpResponse",
    "HttpTransport",
    "TokenProvider",
    "AuthenticatedTransport",
]
