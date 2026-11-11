import asyncio
import json
from pathlib import Path

import pytest

from polyglot_im import ApiClient, HttpResponse, ImError

FIXTURES = Path(__file__).resolve().parents[2] / "protocol/fixtures"


def test_shared_http_envelopes_and_common_prefix():
    asyncio.run(_contract())


async def _contract():
    class Transport:
        status = 201
        body = (FIXTURES / "http-success.json").read_bytes()

        async def execute(self, request):
            assert request.path == "/api/v1/im/notifications"
            return HttpResponse(self.status, self.body)

    transport = Transport()
    api = ApiClient(transport)
    result = await api.request("POST", "/notifications")
    assert result == json.loads(transport.body)["data"]
    transport.status = 409
    transport.body = (FIXTURES / "http-error.json").read_bytes()
    with pytest.raises(ImError) as error:
        await api.request("POST", "/notifications")
    assert error.value.code == "IDEMPOTENCY_CONFLICT"
    assert error.value.status == 409
    transport.status = 200
    transport.body = b'{"code":200,"message":"success","data":null}'
    with pytest.raises(ValueError):
        await api.request("POST", "/notifications")
