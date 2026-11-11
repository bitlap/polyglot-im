"""真实服务集成示例；先在根目录运行 just demo-server。"""

import asyncio
import json
import sys
from pathlib import Path

from polyglot_im import ApiClient, ImClient, NotificationSender, Scope, SqliteStore
from polyglot_im.adapters.httpx import open_httpx_transport
from polyglot_im.transport import AuthenticatedTransport


class Tokens:
    def __init__(self, value):
        self.value = value

    async def token(self):
        return self.value


async def main():
    path = Path(
        sys.argv[1]
        if len(sys.argv) > 1
        else Path(__file__).resolve().parents[2] / ".local-demo/config.json"
    )
    config = json.loads(path.read_text())
    async with open_httpx_transport(config["base_url"]) as transport:

        def api(token):
            return ApiClient(AuthenticatedTransport(transport, Tokens(token)))

        client = ImClient(
            api(config["user_token"]),
            config["base_url"],
            Scope(config["base_url"], config["app_id"], config["user_id"]),
            "python-demo",
            SqliteStore(path.parent / "python.db"),
        )
        await client.initialize()
        sender = NotificationSender(api(config["service_token"]))
        # 幂等键来自业务事件，重试保持一致；真实业务应在本地业务事务写入 Outbox。
        key = "python-demo-task"
        first = await sender.send(
            "alice",
            key,
            "task.completed",
            {"task_id": key, "title": "创作完成", "text": "来自 Python 的通知"},
        )
        duplicate = await sender.send(
            "alice",
            key,
            "task.completed",
            {"task_id": key, "title": "创作完成", "text": "来自 Python 的通知"},
        )
        assert first.message_id == duplicate.message_id
        iterator = client.watch()
        try:
            async with asyncio.timeout(10):
                state = await anext(iterator)
            assert first.message_id in state["messages"]
            await client.mark_read()
            assert client.cache["unread_count"] == "0"
            print("Python HTTP/WS + SQLite + idempotency + read: passed")
        finally:
            await iterator.aclose()
            await client.close()


if __name__ == "__main__":
    asyncio.run(main())
