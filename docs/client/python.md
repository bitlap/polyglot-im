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

需要将业务状态与通知意图一起提交时，使用[业务事务 Outbox](../integration.md#业务事务-outbox)；该文档提供 PostgreSQL 事务与 Worker 的完整示例。

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

`SqliteStore` 使用 Python 标准库 sqlite3，通过 `asyncio.to_thread` 执行阻塞操作；每次操作独立打开并关闭连接，无需另外关闭 store。拒绝空路径、`:memory:` 与 `file:` URI，请传普通文件路径，防止 URI 内存模式在连接关闭后丢失缓存。配置 WAL、synchronous=FULL 和 15 秒 busy_timeout，首次 WAL 初始化的锁竞争有限重试；普通 load 不占写事务，commit/clear 使用 BEGIN IMMEDIATE。消息、会话与水位统一提交，失败回滚；clear 不解析损坏 JSON，只清理目标 scope。

当前与 Flutter/Scala 一样按 scope 保存 JSON 缓存；不宣称逐消息 SQL 索引、数据库级历史分页或跨语言共享文件兼容。缓存路径、目录权限和磁盘加密由宿主配置，SQLite 只用于本地缓存，不替代可信后端 PostgreSQL 业务 Outbox。

`Message` 的 ID/序号保持字符串，未知类型保留原 payload。客户端不执行通知中的任意 URL 或脚本。

## PostgreSQL 客户端缓存

与业务 PostgresOutbox 分开，PostgresStore 实现同一 LocalStore 接口。安装 postgres 可选依赖后，可将已有 AsyncConnectionPool 注入 `PostgresStore(pool, namespace='desktop-installation')`，调用 `await store.initialize()` 显式建表；该方式不关闭借用的池。自有池使用：

```python
from polyglot_im import PostgresStore, open_postgres_store

async with open_postgres_store(
    postgres_dsn, namespace='desktop-installation', max_size=8, timeout=15,
) as store:
    client = ImClient(user_api, base_url, Scope(base_url, app_id, user_id), device_id, store)
    try:
        await client.initialize()
        await client.sync()
    finally:
        await client.close(logout=False)
```

工厂自动初始化，退出或初始化失败关闭自有池；普通导入不加载可选 psycopg 驱动。消息、会话与游标在 READ COMMITTED 事务中持有作用域锁后归并提交，失败回滚，取消按驱动事务语义清理；默认 lock_timeout_ms=15000。schema 默认 snap_flow，表为 im_client_cache；namespace 必须显式配置，与账号 scope 一起隔离缓存。完整约定见[客户端存储](storage.md)。

## 用户文字单聊

会话创建、文字与 Unicode 表情发送、历史和已读回执的完整例子见[单聊集成](../direct-chat.md)。图片与 COS 暂不接入。
