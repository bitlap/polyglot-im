import json
from typing import Any


class JsonCodec:
    content_type = "application/json"

    def encode(self, value: dict[str, Any]) -> str:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    def decode(self, value: str | bytes) -> dict[str, Any]:
        result = json.loads(value)
        if not isinstance(result, dict):
            raise ValueError("wire value must be an object")
        return result
