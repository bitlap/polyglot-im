# 协议与接口

## 状态与约定

当前业务 API **尚未实现**；[OpenAPI](../protocol/openapi.yaml)只描述可运行的健康检查接口。本页其他接口是设计契约，实施时必须补充机器可校验 Schema 和测试后再对接。

协议主版本为 1。HTTPS / WSS，UTF-8 JSON，字段使用 snake_case；ID、64 位序号和计数水位使用字符串；UTC 时间使用以 Z 结尾的 RFC 3339。分页返回 `items`、`next_cursor`、`has_more`，游标不允许客户端自行拼装。

## 消息信封（已实现基础字段）

```json
{
  "schema_version": 1,
  "message_id": "msg_example_001",
  "conversation_id": "conv_example_001",
  "conversation_seq": "9007199254740993",
  "type": "text",
  "payload": {"text": "你好，sf-im"},
  "created_at": "2026-11-11T08:00:00Z"
}
```

Schema 见 [message.schema.json](../protocol/message.schema.json)，共享样例见 [message.json](../protocol/fixtures/message.json)。三端模型验证基础字段，保留 payload 中未知业务结构。当前模型不包含发送者、revision 等规划字段；新增前不能据此开展完整聊天业务。

## 扩展字段与消息类型（规划）

信封新增：`sender`、`client_message_id`、`revision`、`reply_to`、`mentions`、`expires_at`、`edited_at` 和 `status`。`sender` 由服务端根据认证身份生成，不接受普通客户端指定他人身份。

| type | payload 约定 |
| --- | --- |
| text | text；明确字符及字节上限 |
| image | attachment_id、宽高、缩略图资源身份 |
| audio | attachment_id、duration_ms、codec |
| video | attachment_id、cover_id、duration_ms、宽高 |
| file | attachment_id、name、size、mime_type |
| location | latitude、longitude、coordinate_system、label |
| contact | 被分享用户的业务身份及受控快照 |
| rich_text | 内容格式、经过净化的正文、渲染版本 |
| forward_bundle | 可访问消息集合的受控快照 |
| system.* | 服务端产生的成员、公告等事件展示 |
| task.completed / task.failed | 任务身份、状态、业务资源引用 |
| custom.<namespace>.<name> | 应用自定义 payload，需长度限制与安全回退 |

类型专属 Schema 在对应功能实施阶段增加。初始化 Schema 只验证信封，不宣称验证所有消息 payload。

## REST API（规划）

前缀 `/api/v1/im`，用户 API 使用 Bearer 用户令牌，服务端写入使用独立服务凭证与作用域。

| 方法与路径 | 用途 |
| --- | --- |
| POST /session | 交换短期、设备绑定的 WS 票据与协商能力 |
| GET /sync | 从用户游标补拉，支持固定 until 水位 |
| GET /snapshot | 生成带稳定水位的用户会话快照 |
| GET /conversations | 会话分页 |
| POST /conversations | 创建单聊或群聊 |
| GET /conversations/{id}/messages | 按 before_seq 稳定历史分页 |
| POST /conversations/{id}/messages | 幂等发送 |
| PATCH /conversations/{id}/messages/{message_id} | 带 expected_revision 编辑 |
| POST /conversations/{id}/messages/{message_id}/recall | 撤回并生成墓碑 |
| DELETE /conversations/{id}/messages/{message_id} | 用户范围隐藏，显式 scope |
| PUT /conversations/{id}/messages/{message_id}/reactions/{emoji} | 添加回应 |
| DELETE /conversations/{id}/messages/{message_id}/reactions/{emoji} | 取消回应 |
| PUT /conversations/{id}/read | 提交 up_to_seq；取最大值 |
| PATCH /conversations/{id}/preferences | 置顶、免打扰、归档、提醒设置 |
| POST /conversations/{id}/clear | 设置用户隐藏截止水位 |
| GET /conversations/{id}/members | 群成员分页 |
| POST /conversations/{id}/members | 邀请/加入成员 |
| PATCH /conversations/{id}/members/{user_id} | 角色或禁言变更 |
| DELETE /conversations/{id}/members/{user_id} | 退群或移除 |
| POST /conversations/{id}/transfer-owner | 原子转让群主 |
| POST /conversations/{id}/join-requests | 申请入群 |
| POST /conversations/{id}/join-requests/{request_id}/decision | 审批 |
| PUT /conversations/{id}/announcement | 公告 |
| POST /conversations/{id}/dissolve | 解散群 |
| POST /attachments/uploads | 获取上传授权 |
| POST /attachments/{id}/complete | 确认上传并开始扫描 |
| GET /attachments/{id}/download | 校验权限，返回短期下载授权 |
| GET /search/messages | 受权限限制的服务端搜索 |
| PUT /devices/{id}/push-token | 幂等注册/轮换 token |
| DELETE /devices/{id}/push-token | 退出解绑 |
| DELETE /devices/{id}/session | 撤销设备会话 |
| POST /reports | 消息与用户举报 |

请求发送示例：

```json
{
  "client_message_id": "req_unique_per_send_intent",
  "type": "text",
  "payload": {"text": "你好"}
}
```

创建返回 201；同键同内容重放返回 200 且结果一致；同键不同内容返回 409。未完成鉴权、成员检查、禁言检查、内容校验和数据库事务提交前不能返回 accepted。

## WebSocket（规划）

握手使用一次性短期票据，服务端绑定设备。不要在 URL 中传长期访问令牌；反向代理也必须脱敏票据。握手校验浏览器 Origin；票据校验不能被 Origin 校验替代。

事件信封：

```json
{
  "schema_version": 1,
  "event_id": "evt_example_001",
  "cursor": "opaque_user_cursor",
  "type": "message.created",
  "data": {"message_id": "msg_example_001"},
  "occurred_at": "2026-11-11T08:00:00Z"
}
```

持久事件包含 message.created/updated/recalled、reaction.changed、conversation.changed、member.changed、read.updated 和 notification.changed。typing、presence、ping/pong 是独立临时帧，不推进可靠游标。

确认消息包含已原子保存的最高连续游标；不能确认仅收到而未落盘的事件。断线后依赖 HTTP 同步恢复，不要求所有历史通过 WS 重放。

## 错误码与重试（规划）

统一错误体：`code`、面向用户的 `message`、`request_id`、`retryable`，可选 `retry_after_ms` 和校验详情。服务端不能泄露无权资源是否存在。

| HTTP | code 示例 | SDK 行为 |
| --- | --- | --- |
| 400/422 | INVALID_ARGUMENT、UNSUPPORTED_MESSAGE_TYPE | 保留输入，等待用户修正 |
| 401 | TOKEN_EXPIRED | 合并刷新一次；失败进入登录态，停止重连 |
| 403/404 | FORBIDDEN、NOT_FOUND | 不重试；不绕过成员权限 |
| 409 | IDEMPOTENCY_CONFLICT、REVISION_CONFLICT | 不盲重试；重新获取或修正意图 |
| 410 | RESET_REQUIRED | 按快照协议重建再追增量 |
| 413 | PAYLOAD_TOO_LARGE | 转附件上传或提示限制 |
| 429 | RATE_LIMITED | 尊重 Retry-After，加入抖动 |
| 500/502/503/504 | TEMPORARILY_UNAVAILABLE | 有界退避，写请求复用幂等键 |

网络超时不意味着发送失败；客户端先查询或复用同一幂等键重试。用户显式“再次发送”究竟复用旧意图还是创建新消息，要在 UI 和 SDK 中明确区分。

## 兼容性

客户端握手声明支持的协议主版本与能力集合；服务器返回采用版本和限制。新增可选字段、消息类型可向前兼容；删除字段或改变语义需升级主版本。SDK 版本不等于协议版本。接口实现前以本页评审，接口实现后机器契约与本页必须一致。
