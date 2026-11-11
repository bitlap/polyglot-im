# Python Client 集成

Python 3.11+。使用 asyncio、Protocol 和显式资源工厂；网络依赖是可选项：

```sh
uv pip install './python-client[http,postgres]'
# 仓库内完整网络例子：另一终端先运行 just demo-server
uv run --project python-client python examples/python-client/live.py
```

## 发送业务通知

```python
import os
from polyglot_im import ApiClient, NotificationSender
from polyglot_im.transport import AuthenticatedTransport
from polyglot_im.adapters.httpx import open_httpx_transport

class ServiceTokens:
    async def token(self):
        return os.environ['IM_SERVICE_TOKEN']

async def publish_task(task_id, user_id):
    async with open_httpx_transport(os.environ['IM_BASE_URL']) as transport:
        sender = NotificationSender(ApiClient(AuthenticatedTransport(transport, ServiceTokens())))
        return await sender.send(user_id, f'task:{task_id}:completed', 'task.completed', {
            'task_id': task_id, 'title': '创作完成', 'text': '作品已生成',
            'action': {'type': 'open_task', 'id': task_id},
        })
```

应复用宿主生命周期内的连接池；例子用短生命周期便于说明。`HttpxTransport` 借用外部 AsyncClient，`open_httpx_transport` 拥有并关闭连接池；两者都禁止跨源重定向。刷新认证通过 `ApiClient(..., refresh_auth=async_callback)` 注入。取消不会转换成网络重试。

业务事务与 IM 是两个系统。应在**业务数据库事务内**同时提交任务状态和待发送记录；后台 Worker 使用事件 ID 调用 sender，成功后确认本地 Outbox。崩溃后重放同一 ID。SDK 的重试不能代替业务数据库的事务 Outbox，服务端发送事务负责消息、事件和 APNs Outbox 的原子性。见[业务接入](../integration.md)。

## PostgreSQL 业务存储

使用 `PostgresOutbox(pool)` 借用宿主的 `psycopg_pool.AsyncConnectionPool`，或通过 `open_postgres_outbox(conninfo, min_size=1, max_size=8, timeout=15, lease_seconds=120)` 显式创建并关闭池。DSN 由配置传入；生产连接可在 DSN 中指定 `sslmode=verify-full` 和 CA。`initialize()`、`enqueue()`、`drain_once()` 均为异步调用，事务示例见[业务接入](../integration.md)。

`enqueue` 必须加入调用方业务事务，不能用单独的 IM 数据库连接替代业务事务。原 SQLite 业务 Outbox 接口替换为 PostgreSQL 接口；设备缓存的 `SqliteStore` 保留，不用于后端业务存储。

## 用户接收、缓存和已读

```python
from polyglot_im import ImClient, Scope, SqliteStore

client = ImClient(user_api, base_url, Scope(base_url, app_id, user_id),
                  device_id, SqliteStore('/app/data/im.db'))
try:
    await client.initialize()
    print(client.messages, client.cache['unread_count'])
    await client.history(before_seq=None, limit=50)
    await client.mark_read()  # 请求前捕获当前 latest_seq
    async for cache in client.watch():
        consume(cache)
finally:
    await client.close(logout=True, clear_cache=False)
```

`user_api` 与上面的构造一致，但注入用户凭证。`watch()` 每个实例只允许一个消费者，持久化后才 yield/ack；取消任务或退出迭代器释放 WebSocket。进程重启用相同 SQLite 路径与 scope 继续同步。只运行 `sync()` 也能实现轮询客户端。`history()` 返回 items、has_more、next_before_seq；服务端快照只含最新 100 条，旧消息按页获取。

`Message` 的 ID/序号保持字符串，未知类型保留原 payload。客户端不执行通知中的任意 URL 或脚本。

## 用户文字单聊

会话创建、文字与 Unicode 表情发送、历史和已读回执的完整例子见[单聊集成](../direct-chat.md)。图片与 COS 暂不接入。
