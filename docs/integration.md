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

Scala 的 `JdbcOutbox[F]` 使用宿主 PostgreSQL DataSource，`initialize` 建表，`enqueue(connection, NotificationIntent(...))` 直接加入调用方的业务 JDBC 事务，`drainOnce(sender)` 由宿主 Resource/调度器周期调用。enqueue 必须在 Async.blocking 内与业务 SQL 一起执行；该方法不关闭连接、不 commit，不另开全局运行时。PostgreSQL JDBC 驱动随 Scala Client 声明，连接池由宿主创建和管理。数据库架构不同可实现自己的 Outbox，使用相同稳定 ID 和发送 API。

业务 Outbox 与 APNs Outbox 的保证及停止条件见[各链路保证](delivery.md#各链路保证)。

## 复用业务登录

默认支持宿主现有不透明 Bearer，无需 App/小程序更换登录协议或申请额外 IM 用户 JWT。用户鉴权扩展点为 `ports.UserAuthenticator`；独立服务可通过 `JwtAuthenticator` 的 userAuthenticator 注入，嵌入宿主也可替换 `ServerComponents.authenticator`。外部用户实现只能返回当前应用且未过期的 user 身份；IM 清除生产者 types/recipients 范围。生产者继续使用独立服务 JWT，不能拿业务用户会话发系统通知。

HOCON 设置 `polyglot-im.auth.introspection-url` 后启用默认 `HttpSessionAuthenticator`，同时关闭本地用户 JWT；未设置时使用用户 JWT 模式。

内省固定 GET URL + Authorization: Bearer，不接受客户端指定校验 URL，不跟随重定向，要求 HTTPS（仅 loopback 允许 HTTP），5 秒超时、响应最多 16 KiB。端点返回 200，裸对象或 data 对象必须含 active=true、匹配的 app_id、非空 user_id 和未过期 exp（Unix 秒；兼容 RFC3339 expires_at）。详见 [身份样例](../protocol/fixtures/introspection.json) 和 [Schema](../protocol/introspection.schema.json)。仅含 user_id 的响应无效。

每次 HTTP 请求重新校验业务会话，不缓存业务 token；WS ticket 须在 30 秒内使用一次；内省身份有效期最多 5 分钟，已建立的 WS 到身份期限断开，再连接时重新验证。业务会话撤销可能在该身份期限内延迟生效。

SnapFlow 的 App `/api/v1/auth/app/login` 和小程序 `/api/v1/mini/login` 都返回 Redis 不透明会话。二者共用 `SessionManager` 的身份命名空间，配置如下（令牌和密钥不写入示例）：

```hocon
polyglot-im.auth {
  app-id = "snapflow"
  introspection-url = "https://business.example.com/api/v1/im/identity"
  # 外部会话模式不需要 user-jwt-secret。
  service-jwt-secret = ${?IM_SERVICE_JWT_SECRET}
  cursor-secret = ${?IM_CURSOR_SECRET}
}
```

SnapFlow 设置 SF_IM_PUBLIC_URL、SF_IM_SERVICE_URL、SF_IM_APP_ID=snapflow、SF_IM_SERVICE_TOKEN_FILE 后，`/api/v1/notifications/config` 返回 base_url、app_id、auth_mode=opaque_bearer。IM 的 auth.app-id 与 SF_IM_APP_ID 必须一致。服务 JWT 由可信部署方按[协议](protocol.md)生成，写入受保护文件；不通过客户端分发。App 使用 Flutter SDK 的动态 TokenProvider，小程序使用[uni-app SDK](../uniapp-client/README.md)的 tokenProvider，均直接传当前业务会话。宿主的 `/api/v1/im/identity` 只返回校验通过的当前用户，不接受请求中的 user_id 覆盖登录身份。

同一个 IM app_id 只对应一个业务用户命名空间。缓存以业务环境、IM 地址、app_id、user_id 隔离；切换账号立即关闭旧实例，拒绝迟到回包。用户主动退出先尝试解绑 IM 设备，再撤销业务会话；解绑失败也应释放本地资源并退出登录，由宿主记录失败并处理服务端会话撤销。被动过期关闭本地实例。

小程序在前台使用 WebSocket 与兜底轮询，后台暂停两者，支持通知列表、历史、已读与 open_item/open_user/open_review 白名单跳转；原生 App 另支持 APNs。小程序不能通过 APNs 自行产生 iOS 原生系统通知。

## 首次接入

宿主按当前接口接入用户登录、业务事件和 App 消息页。无需导入旧通知、保留旧 messageId 映射或提供双接口兼容。服务端按当前表结构初始化；不提供旧 IM 数据升级链。

仓库的 iOS 示例展示 Client 接入。真实业务事件生产接线与线上部署在宿主仓库完成。
