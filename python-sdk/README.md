# Polyglot IM Python SDK

Python 3.11+ 异步通知 SDK，提供通知发送、HTTP/WebSocket 同步、SQLite 本地消息缓存和 PostgreSQL 业务事务 Outbox。宿主管理事件循环、身份与资源生命周期，网络和数据库适配器支持注入。

## 使用示例

在仓库根目录安装网络与业务数据库依赖：

```sh
uv pip install './python-sdk[http,postgres]'
```

```python
import asyncio
import os
from polyglot_im import ApiClient, NotificationSender
from polyglot_im.adapters.httpx import open_httpx_transport
from polyglot_im.transport import AuthenticatedTransport


class ServiceTokens:
    async def token(self):
        return os.environ["IM_SERVICE_TOKEN"]


async def main():
    async with open_httpx_transport(os.environ["IM_BASE_URL"]) as transport:
        api = ApiClient(AuthenticatedTransport(transport, ServiceTokens()))
        sender = NotificationSender(api)
        await sender.send(
            "user-123",
            "task:456:completed",
            "task.completed",
            {
                "task_id": "456",
                "text": "创作完成",
            },
        )


asyncio.run(main())
```

业务通知通过 `PostgresOutbox(pool)` 借用宿主异步连接池，或用 `open_postgres_outbox(conninfo)` 显式创建池。将 `enqueue()` 与业务更新放在同一个 PostgreSQL 事务，后台 Worker 调用 `drain_once()`；DSN、TLS、池大小、超时及租约均可配置。SQLite 仅用于设备本地缓存，服务凭证不进入 App。

## 运行例子与测试

先在仓库根目录执行 `just demo-server`，再执行：

```sh
uv run --project python-sdk --locked python examples/python-sdk/live.py
just test-python
```

[可运行示例](../examples/python-sdk/README.md) · [完整集成](../docs/sdk/python.md) · [业务 Outbox 示例](../docs/integration.md) · [返回 Polyglot IM 项目首页](../README.md)

## 用户单聊

支持文字与 Unicode 表情、唯一双人会话、可靠同步、历史分页和已读回执。完整接口与使用例子见[单聊集成](../docs/direct-chat.md)。图片与 COS 接入暂不支持。

HTTP 使用 `code/message/data` 通用信封，SDK 自动解包；服务端公共前缀由 http4s Router 统一挂载。完整约定见[HTTP 协议](../docs/protocol.md)。
