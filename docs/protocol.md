# 协议与接口

HTTP/JSON 与 WebSocket 使用 UTF-8。协议主版本 schema_version=1，包版本 0.1.0；不是同一版本。请求见 [OpenAPI](../protocol/openapi.yaml)，消息信封见 [message.schema.json](../protocol/message.schema.json)，通知发送见 [notification.schema.json](../protocol/notification.schema.json)。

投递语义为保留范围内可重放的至少一次同步，结合幂等入库与缓存去重；没有端到端恰好一次承诺。HTTP 成功、设备 ack、用户已读和 APNs 接受分别确认不同事实，详细前提及重试/恢复规则见[一致性与可达性契约](delivery.md)。本文接口格式保持不变。

## 通知契约

| 类型 | 必填 payload | 建议 action |
| --- | --- | --- |
| task.completed / task.failed | task_id | open_task |
| comment.created / comment.replied | item_id、comment_id | open_item |
| like.created / favorite.created | item_id | open_item |
| system.notice | 无额外必填 | none |
| custom.<namespace>.<name> | JSON 对象，由宿主定义 | 允许的 action |

title/text 可选，长度各不超过 2048 Unicode 码点；payload 编码后不超过 16 KiB，整个 JSON 请求不超过 64 KiB。ID/设备名/幂等键为非空字符串，不含控制字符，最长 200 码点。action.type 只允许 open_photo、open_item、open_task、none，前 3 种要求 id。客户端再次请求业务资源检查权限，消息不能授予访问权限。

发送必须用服务身份，user 不能发系统通知。`client_message_id` 在同 app/sender 下唯一；重试保持接收者、类型和规范化 JSON 内容一致。首次返回 201，幂等返回 200，内容冲突 409。通知 Unicode 样例见 [fixture](../protocol/fixtures/notification.json)。

消息返回 message_id、conversation_id、conversation_seq、type、payload、created_at、schema_version。序号用字符串保留 64 位精度，时间为 UTC RFC 3339。未知 type/payload 原样保留并展示通用卡片；未知可选字段忽略，主版本不兼容则拒绝。

## 身份

Authorization: Bearer，HS256 JWT：

```json
{"iss":"polyglot-im","aud":"polyglot-im","app_id":"your-app","sub":"user-or-service-id","role":"user","exp":1900000000}
```

issuer 与服务配置一致。用户、服务使用不同签名密钥；service 必须额外包含 types 与 recipients 字符串数组，只有明确授权的类型和接收者可以发送，`*` 表示授权全部。密钥由后端安全管理，不进入 App。可选 nbf 会验证。示例 exp 只是字段说明，生产应签发短期令牌。

可选业务登录 introspection 见[集成说明](integration.md)。服务端只信任自己的固定鉴权 URL；不能由客户端提交用户身份作为认证事实。单部署固定 app_id；多应用可分别部署/配置独立签名材料。

## 路由

统一前缀 `/api/v1/im`：

| 方法 / 路由 | 身份 | 行为 |
| --- | --- | --- |
| POST /notifications | service | 幂等通知发送 |
| GET /messages | user | limit=1..100、before_seq 排他分页 |
| GET /messages/{id} | user | 仅当前用户可见 |
| GET /state | user | 计数和当前水位 |
| GET /snapshot | user | 最新 100 条 + 同一事务的高水位 |
| GET /sync?cursor=... | user | 最多 100 个增量事件，含 has_more |
| PUT /read | user | up_to_seq，阅读水位只增 |
| POST /session | user | device_id，返回短期一次性 WS ticket |
| PUT /devices/{device}/push-token | user | token/null、muted、preview |
| GET /devices/{device}/preferences | user | 查询静音与预览偏好，不返回 token |
| DELETE /devices/{device}/session | user | 登出解绑、撤销设备票据 |
| PUT /devices/{device}/ack | user | cursor 接收确认 |
| GET /ws?ticket=...&cursor=... | ticket | WS 升级 |

状态包含 latest_seq、read_seq、unread_count、conversation_id、cursor、event_position；均保持序号字符串。cursor 是服务端不透明签名，客户端不应构造/解析；event_position 用于本地防止乱序页回退。同步页的 cursor 是**这一页处理完的位置**，不能用全局水位跳过 has_more 中的事件。页内计数取该次查询时的最新服务端状态，可以先于这一页的消息列表；持续追赶后视图一致。

事件包含 event_id/type/data/cursor/occurred_at。message.created 的 data 是 Message，read.updated 的 data 含 read_seq。客户端将已知事件应用及游标一起提交；未知事件类型也必须推进游标。RESET_REQUIRED 时取快照，禁止仅把游标改到最新后忽略重建。

## WebSocket

POST session 先验证身份并激活设备；ticket 数据库仅保存哈希，30 秒内使用一次，绑定 app/user/device/身份到期时间。ticket 不能用于 HTTP API。WS 服务端文本帧：

```json
{"type":"sync","data":{"cursor":"opaque","event_position":"42","latest_seq":"3","read_seq":"2","unread_count":"1","conversation_id":"notice_...","has_more":false,"events":[]}}
```

本地事务成功后回复 `{"cursor":"opaque"}`。接收确认不得超出已发位置；服务端接受的确认不会被解释为用户已读。心跳 20 秒，SDK 自动重连；不能依赖 WS 握手事件代替 HTTP 补拉。反向代理须支持 Upgrade，禁止记录 ticket 查询参数。

## 错误、重试与兼容

错误统一为数字 code、message、data=null、error_code、request_id、retryable；error_code 承载稳定业务错误码。401 认证失败，403 权限不足，404 消息不存在/不可见，409 幂等冲突，410 RESET_REQUIRED，413 内容超限，422 参数无效，429 集群身份配额限流，503 依赖不可用或 OVERLOADED 过载，其他 5xx 服务异常。不存在资源与越权读取统一返回 404，不泄露其他用户消息。

网络错误、429、500/502/503/504 有限指数退避，发送复用原幂等键。业务参数/权限/冲突不能盲重试。认证刷新由宿主注入，失败后交给重新登录。默认每个应用/角色/身份每分钟 user 600、service 120 个请求，多个实例共享 PostgreSQL 配额；网关另行限制未认证流量和连接数。SDK 连接错误不会无限占用无界队列。错误体结构与状态码保持兼容；OVERLOADED 按现有可重试 503 处理，实例数量不改变消息/游标格式，WS 每页最多 25 个事件，仍按 has_more 连续追赶。

SDK 用户请求还携带 X-IM-App-ID / X-IM-User-ID 预期身份头（UTF-8 URL 编码）；服务端与鉴权结果不符时返回 403 IDENTITY_MISMATCH。它们不是认证凭证，用于拒绝账号切换后旧实例误用新凭证。更新推送 token 前必须先创建活跃 device session；登出后的迟到更新返回 409 DEVICE_SESSION_CLOSED，不重新激活设备。

## 文字单聊协议

chat.text 使用现有 v1 信封，payload 为服务端生成的 sender_id 和 Unicode text；发送只接受 text。新增 conversation.updated 事件、会话摘要及 chat_unread_count 为兼容扩展；旧客户端可忽略未知事件并推进游标，但展示单聊须升级 SDK。历史和通知计数不混用，完整接口及快照范围见[单聊集成](direct-chat.md)。共享样例位于 protocol/fixtures/direct-message.json。

## HTTP 公共前缀与响应信封

服务端在 http4s `Router` 统一挂载 `/api/v1/im`，由 Server.apiPrefix 定义。Api.routes 只声明 `/notifications`、`/conversations`、`/session`、`/ws` 等相对路径；组合根通过 Server.app 完成挂载，不在每条业务路由重复拼接前缀。`/health/live` 和 `/health/ready` 位于根路径，便于探针独立访问。未挂载前缀及重复前缀均不匹配业务路由。

成功 HTTP JSON 采用通用信封，data 内保留原有消息、分页、状态及会话字段：

```json
{"code":200,"message":"success","data":{"ok":true}}
```

新消息仍返回 HTTP 201，幂等重试返回 HTTP 200；成功信封中的 code 固定为 200。错误保留真实 HTTP 状态，code 为对应数字状态码，data 为 null，error_code 是稳定业务错误码：

```json
{"code":409,"message":"idempotency key already used","data":null,"error_code":"IDEMPOTENCY_CONFLICT","request_id":"request-id","retryable":false}
```

健康探针、未知路由和过载拒绝也采用同一信封。客户端依据 HTTP 状态执行鉴权刷新或退避，业务判断使用 error_code；不把错误包装成 HTTP 200。三端 SDK 自动解包 data，原有 Message、缓存、领域 API 返回类型及 ImError.code 的业务码语义保持不变。SDK 兼容旧版未包装 HTTP 响应；直接使用 HTTP 的接入方需更新到 data 层取值，并从 error_code 读取业务错误。升级顺序为先部署兼容新旧响应的 SDK，再切换服务端信封；旧 SDK 不会自动适配新服务端。协议共享样例为 protocol/fixtures/http-success.json、http-error.json，Schema 为 protocol/http-response.schema.json。

WebSocket 升级成功仍为 HTTP 101；文本帧保持 `{type:"sync",data:SyncPage}`，不套 HTTP 信封。数据库存储的领域 JSON、APNs 载荷、鉴权服务的外部 introspection 协议也不套此信封。
