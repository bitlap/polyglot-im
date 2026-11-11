"""多语言消息 SDK 的 Python 异步实现与兼容公共入口。"""

from .adapters.codec.json import JsonCodec
from .adapters.storage.postgres import PostgresOutbox, open_postgres_outbox
from .adapters.storage.sqlite import SqliteStore
from .api.client import ImClient
from .api.http import ApiClient
from .common.errors import ImError
from .config.retry import RetryPolicy
from .domain.identity import Scope
from .domain.message import Message
from .ports.api import RemoteApi
from .ports.codec import WireCodec
from .ports.notifications import NotificationPublisher
from .ports.socket import SocketConnector, SocketSession
from .ports.storage import LocalStore
from .ports.transport import HttpRequest, HttpResponse, HttpTransport, TokenProvider
from .service.auth import AuthenticatedTransport
from .service.notifications import NotificationSender

__all__ = [
    "Message",
    "Scope",
    "ImError",
    "ApiClient",
    "ImClient",
    "NotificationSender",
    "HttpRequest",
    "HttpResponse",
    "HttpTransport",
    "TokenProvider",
    "AuthenticatedTransport",
    "RemoteApi",
    "SocketConnector",
    "SocketSession",
    "LocalStore",
    "WireCodec",
    "NotificationPublisher",
    "JsonCodec",
    "RetryPolicy",
    "SqliteStore",
    "PostgresOutbox",
    "open_postgres_outbox",
]
