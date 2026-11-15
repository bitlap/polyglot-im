"""多语言消息 Client 的 Python 异步实现与兼容公共入口。"""

from .adapters.cache.postgres_cache import PostgresCache, open_postgres_cache
from .adapters.cache.sqlite_cache import SqliteCache
from .adapters.codec.json import JsonCodec
from .adapters.store.postgres_outbox import PostgresOutbox, open_postgres_outbox
from .api.client import ImClient
from .api.http import ApiClient
from .common.errors import ImError
from .config.retry import RetryPolicy
from .domain.identity import Scope
from .domain.message import Message
from .ports.api import RemoteApi
from .ports.client_cache import ClientCache
from .ports.codec import WireCodec
from .ports.notifications import NotificationPublisher
from .ports.socket import SocketConnector, SocketSession
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
    "ClientCache",
    "WireCodec",
    "NotificationPublisher",
    "JsonCodec",
    "RetryPolicy",
    "SqliteCache",
    "PostgresCache",
    "open_postgres_cache",
    "PostgresOutbox",
    "open_postgres_outbox",
]

# 旧版公共名称保留为别名，缓存实现统一使用 Cache 命名。
LocalStore = ClientCache
SqliteStore = SqliteCache
PostgresStore = PostgresCache
open_postgres_store = open_postgres_cache
__all__ += ["LocalStore", "SqliteStore", "PostgresStore", "open_postgres_store"]
