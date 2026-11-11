import hashlib
import json
from dataclasses import dataclass


@dataclass(frozen=True)
class Scope:
    """环境、应用和用户组成的缓存隔离边界；不同作用域的数据不能混用。"""

    environment: str
    app_id: str
    user_id: str

    @property
    def key(self) -> str:
        """派生当前作用域的稳定隔离键，不把不同应用或用户的缓存混在一起。"""
        return hashlib.sha256(
            json.dumps([self.environment, self.app_id, self.user_id]).encode()
        ).hexdigest()
