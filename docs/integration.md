# 业务服务与现有 App 接入

发送、同步、设备确认及业务执行的保证不同；接入前先阅读[消息一致性与可达性契约](delivery.md)。消费更新后扣款、发券等业务副作用，需要宿主自己的事务去重或下游幂等 API，不能由 Client ack 推断已经处理一次。

## 业务事务 Outbox

任务完成、评论、回复、点赞、收藏和系统通知共用通知发送 API。事件类型及必填字段见[协议](protocol.md)。稳定事件 ID 应来自业务数据库；对同一事件的重试必须保留 ID、接收者、类型和 payload。业务取消、删除或权限变更不能依赖一个已发送通知授予权限。

Python 业务存储使用 PostgreSQL，异步驱动通过可选依赖安装：

```sh
uv pip install './python-client[http,postgres]'
```

```python
import os
from polyglot_im import PostgresOutbox, open_postgres_outbox

async def produce_and_drain(task_id, user_id, sender):
    async with open_postgres_outbox(
        os.environ['POLYGLOT_IM_BUSINESS_DATABASE_DSN'],
        min_size=1, max_size=8, timeout=15, lease_seconds=120,
    ) as outbox:
        await outbox.initialize()  # 显式建表，可放在启动初始化中
        async with outbox.pool.connection() as connection, connection.transaction():
            await connection.execute(
                'UPDATE task SET status=%s WHERE id=%s', ('completed', task_id)
            )
            await outbox.enqueue(connection, f'task:{task_id}:completed', user_id,
                                 'task.completed', {'task_id': task_id, 'text': '创作完成'})
        # 宿主管理的 Worker 周期调用；与业务事务提交分开。
        while await outbox.drain_once(sender):
            pass
```

`enqueue` 是异步方法，必须借用调用方已开启的业务事务，不自行提交。业务回滚时通知意图一起回滚。宿主已有 `psycopg_pool.AsyncConnectionPool` 时直接构造 `PostgresOutbox(pool)`；适配器不打开或关闭借用的池。`open_postgres_outbox` 工厂显式拥有连接池，正常、异常和取消退出均关闭池；实际服务应在应用生命周期内复用池。

DSN 由宿主显式传入，支持 PostgreSQL URI 或 libpq 连接串，包含 host、port、dbname、user、password、sslmode 等参数；不读取全局环境或静默连接默认数据库。IM 服务数据库与业务数据库可分别配置，Outbox 必须与业务表处于同一数据库事务。连接信息只保存在后端，不下发 App。

`drain_once` 使用 `FOR UPDATE SKIP LOCKED` 并发领取及可配置租约；领取提交后才发送 HTTP，发送期间不占用数据库事务。崩溃或取消后租约到期重放，过期 Worker 的确认不能覆盖新租约。认证与网络失败保留 pending，非重试参数/权限冲突进入 dead。修复后人工重置 state/next_at，保留原 event_id；表不保存凭证，错误只保存稳定错误码。Python 与 Scala 业务 Outbox 均默认使用 snap_flow.im_producer_outbox，不依赖连接的 search_path。Python 可通过 schema 参数显式选择独立的宿主或测试命名空间。

Scala 的 `JdbcOutbox[F]` 使用宿主 PostgreSQL DataSource，`initialize` 建表，`enqueue(connection, NotificationIntent(...))` 直接加入调用方的业务 JDBC 事务，`drainOnce(sender)` 由宿主 Resource/调度器周期调用。enqueue 必须在 Async.blocking 内与业务 SQL 一起执行；该方法不关闭连接、不 commit，不另开全局运行时。JDBC 驱动和连接池由宿主提供。数据库架构不同可实现自己的 Outbox，使用相同稳定 ID 和发送 API。

业务 Outbox 与 IM 服务的 APNs Outbox 是两层：前者保障跨系统业务事件，后者保障消息提交后推送任务不会丢失。都不能提供无条件的“恰好一次外部副作用”；最终依赖幂等键与事务确认。

## 复用业务登录

优先由现有业务后端签发有效期短的 IM 用户 JWT。用户/服务签名密钥分离，claim 格式见[协议](protocol.md)。IM 不提供公开签发端点。

现有不透明 Bearer 可配置 `POLYGLOT_IM_INTROSPECTION_URL` 指向业务端受保护的 `/api/v1/me`；IM 服务用固定 URL + Bearer 验证身份，返回 200 且含 `user_id` 或 `data.user_id` 才通过。可选的 active=false 或 app_id 不符会拒绝。生产必须 HTTPS，HTTP 只允许 loopback；不允许重定向。身份验证失败会拒绝，不会相信 App 自报 user_id。WS ticket 只延续至该次验证后的 5 分钟会话期限，之后重新验证。

Flutter TokenProvider 可复用宿主的登录凭证。以业务服务 URL 加 IM 服务 URL 作为环境隔离基础；同一个 IM app_id 必须只对应一个业务用户命名空间。用户退出时先在 IM 解绑设备，再清理业务凭证。生产/本地环境禁止共用账户 scope 或签名密钥。

## 首次接入

IM 尚未上线，宿主按当前接口接入用户登录、业务事件和 App 消息页。无需导入旧通知、保留旧 messageId 映射或提供双接口兼容。服务端按当前表结构初始化；不提供旧 IM 数据升级链。

仓库的 iOS 示例展示 Client 接入。真实业务事件生产接线与线上部署在宿主仓库完成，跟踪见 [宿主业务通知与 App 接入 #4](https://github.com/bitlap/polyglot-im/issues/4)。
