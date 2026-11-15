"""0.1.x 兼容入口；实现分别位于 api/service/domain/ports/adapters。"""

from .adapters.cache.sqlite_cache import SqliteCache
from .api.client import ImClient
from .api.http import ApiClient
from .common.errors import ImError
from .config.protocol import API_PREFIX
from .domain.cache import apply_page, empty_cache
from .domain.identity import Scope
from .ports.client_cache import ClientCache
from .service.notifications import NotificationSender

__all__ = [
    "ApiClient",
    "ImClient",
    "NotificationSender",
    "ImError",
    "Scope",
    "apply_page",
    "empty_cache",
    "ClientCache",
    "SqliteCache",
    "API_PREFIX",
]

# 旧版公共名称保留为别名，缓存实现统一使用 Cache 命名。
LocalStore = ClientCache
SqliteStore = SqliteCache
__all__ += ["LocalStore", "SqliteStore"]
