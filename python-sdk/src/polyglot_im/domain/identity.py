import hashlib
import json
from dataclasses import dataclass


@dataclass(frozen=True)
class Scope:
    environment: str
    app_id: str
    user_id: str

    @property
    def key(self) -> str:
        return hashlib.sha256(
            json.dumps([self.environment, self.app_id, self.user_id]).encode()
        ).hexdigest()
