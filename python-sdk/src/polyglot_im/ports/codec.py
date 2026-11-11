from typing import Any, Protocol


class WireCodec(Protocol):
    content_type: str

    def encode(self, value: dict[str, Any]) -> str | bytes: ...
    def decode(self, value: str | bytes) -> dict[str, Any]: ...
