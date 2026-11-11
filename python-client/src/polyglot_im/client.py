"""0.1.x 兼容入口；实现分别位于 api/service/domain/ports/adapters。"""

from .adapters.storage.sqlite import SqliteStore
from .api.client import ImClient
from .api.http import ApiClient
from .common.errors import ImError
from .config.protocol import API_PREFIX
from .domain.cache import apply_page, empty_cache
from .domain.identity import Scope
from .ports.storage import LocalStore
from .service.notifications import NotificationSender

__all__ = [
    "ApiClient",
    "ImClient",
    "NotificationSender",
    "ImError",
    "Scope",
    "apply_page",
    "empty_cache",
    "LocalStore",
    "SqliteStore",
    "API_PREFIX",
]
