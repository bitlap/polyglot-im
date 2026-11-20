# 用户单聊：文字与表情

单聊支持 Scala、Python、Dart、uni-app，以及 Flutter 的会话列表和聊天页。图片、贴图、COS 上传与资源下载暂不支持。Unicode 表情直接包含在文本中，不需要独立上传或附件消息。

消息顺序、幂等重试、离线重建与已读边界统一见[消息一致性与可达性契约](delivery.md)。

## 身份与路由

客户端持有本人 User Token，以 `peer_user_id` 创建会话。同一应用的一对不同用户，无论谁先创建、重试或落在哪个实例，均返回同一个 `conversation_id`。消息发送、读取和标记已读均在服务端校验成员；非成员及跨应用访问返回 404。`payload.sender_id` 由服务端从令牌生成，客户端只传 `payload.text`，不能伪造发送者，也不能调用服务通知接口。

未启用 `direct-chat.enabled` 的旧部署保留按用户 ID 发起单聊的行为。接入业务 App 时必须开启单聊控制并配置可信用户关系目录；开启后逐次校验存在性、接收权限及双向拉黑，目录异常返回503，不降级放行。已启用的数据库不允许通过关闭该配置退回无权限模式。

通知和单聊共用账户游标与 WebSocket，分别保存消息、序号和已读状态。跨实例投递流程见[系统架构](architecture.md#多实例提交与投递)。

## HTTP 接口

所有路径以 `/api/v1/im` 开头，HTTP 返回的领域数据位于通用信封 data 内，Client 自动解包。要求用户身份；服务身份不能冒充用户发送单聊。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| POST /conversations/direct | peer_user_id | 会话状态；不允许自己与自己单聊 |
| GET /conversations | after、limit | items、has_more、next_after；按会话 ID 排序 |
| POST /conversations/{id}/messages | client_message_id、type=chat.text、payload={text} | 新消息 201；相同幂等重试 200 |
| GET /conversations/{id}/messages | before_seq、limit | items、has_more、next_before_seq、conversation |
| PUT /conversations/{id}/read | up_to_seq | 更新后的会话状态 |
| GET /messages/{id} | 消息 ID | 鉴权获取通知或单聊消息，供推送点击使用 |

文本为 1～2048 个 Unicode 码点，支持换行、中文、组合表情及肤色修饰符；拒绝全空白和 NUL。发送载荷只能包含 text；图片类型、附件和客户端传入的 sender_id 被拒绝。长度不是屏幕上可见字符的数量，一个组合表情可能包含多个码点。

幂等键作用域为应用、用户、client_message_id，跨会话生效；相同键更改目标或内容返回 409。超时和应用重试必须保留原键和原文。Client 不自动将用户草稿和待发送队列持久化；宿主可用本地数据库保存发送意图。Flutter 组件在页面存活期间保留失败消息的键，原文重试不重复创建消息。

## 状态、同步与缓存

每个会话包含 conversation_id、kind=direct、member_ids、revision、latest_seq、read_seq、peer_read_seq、unread_count 和 messages。数字均为十进制字符串。unread_count 只统计对方发来且尚未读的消息，不能简单用 latest_seq-read_seq 计算。对方公开已读且水位覆盖本方消息序号时，聊天页显示“已读”；发送响应仅表示服务器持久化成功，不代表对方设备已接收。

`conversation.updated` 事件的 data 是接收者视角的会话状态及新增消息数组；创建、发送和已读更新均通知双方。会话 revision 单调递增；四种语言缓存拒绝旧历史响应覆盖较新的已读状态，同时合并历史消息。单聊消息保存在 `cache.conversations[conversation_id].messages`，通知仍保存在 `cache.messages`。缓存与账户游标原子保存后才确认接收。

首次快照或游标过期重建时，仅带至多 100 个会话摘要，仅带每个会话最后一条消息，不包含完整单聊历史；conversations_has_more 表示尚有会话，使用会话分页补齐。打开会话后调用 conversationHistory 获取历史，页面向前分页加载。重建缓存会清空旧会话消息，服务器历史仍可重新读取。HTTP 历史、创建和已读响应不推进账户游标，避免跳过其他消息事件。

全局状态中的 unread_count 仍仅指通知，chat_unread_count 是所有单聊未读总数，不受会话列表分页影响。Client 更新 iOS 角标时使用两者之和，最大显示 9999。单聊推送复用设备静音、预览开关、租约和重试机制；已读、登出或解绑会抑制尚未投递的任务，已交给 Apple 的通知无法撤回。APNs 真机验收仍需部署账号与设备。

## Client 示例

以下 client 均为完成 initialize 的本人客户端；构建和登录方式见各 Client README。

```python
conversation = await client.create_direct("bob")
cid = conversation["conversation_id"]
message = await client.send_text(cid, "order-2026-message-1", "你好 😊👨‍👩‍👧‍👦")
page = await client.conversation_history(cid, limit=50)
await client.mark_conversation_read(cid, page["conversation"]["latest_seq"])
```

```scala
for {
  conversation <- client.createDirect("bob")
  id = conversation.hcursor.get[String]("conversation_id").toOption.get
  message <- client.sendText(id, "order-2026-message-1", "你好 😊👍🏽")
  page <- client.conversationHistory(id)
  _ <- client.markConversationRead(id, page.hcursor.downField("conversation").get[String]("latest_seq").toOption.get)
} yield message
```

```dart
final conversation = await client.createDirect('bob');
final id = conversation['conversation_id'] as String;
await client.sendText(id, clientMessageId: 'order-2026-message-1', text: '你好 😊👍🏽');
final page = await client.conversationHistory(id);
await client.markConversationRead(id, page['conversation']['latest_seq'] as String);
```

Flutter 在页面中使用 `ImDirectChat(client: client, conversationId: id)`；会话列表使用 `ImConversations(client: client, onOpen: openChat)`。宿主启动实时同步，页面仅在前台、当前路由且列表位于最新消息位置时自动上报已显示的阅读水位；浏览旧历史期间新到消息保持未读，回到最新位置后再确认。请将不同账号绑定到不同的 ImClient，并在退出时释放旧页面及客户端。

## 本机双人演示

根目录运行 `just demo-server`，临时 PostgreSQL 和测试令牌写入被 Git 忽略的 .local-demo。Alice 使用 flutter.json，Bob 使用 flutter-bob.json；服务凭证只在命令行示例中使用，不写入移动端配置。

```sh
cd examples/flutter-client
flutter run -d <alice-device> --dart-define-from-file=../../.local-demo/flutter.json
flutter run -d <bob-device> --dart-define-from-file=../../.local-demo/flutter-bob.json
```

进入“单聊”，点击发起单聊并输入对方用户 ID。两个模拟器可使用各自设备的 SQLite 缓存；真机需要将本机回环地址替换为可达的开发地址，并按 iOS 网络策略配置测试环境。退出 demo-server 后临时数据库被释放，下一次演示应使用新生成的配置。

## 分布式边界

单聊写入先获取幂等事务锁，再锁会话行，最后按相同的用户 ID 顺序锁双方账户状态行；其他控制事务不反向获取会话锁。每条会话内消息及双方游标按提交顺序分配，重复请求不增加序号或未读。发送路径对未读计数作事务增量更新，已读时只统计已覆盖区间，避免每次发送扫描全部未读历史。同一用户多会话共享账户游标锁，因此超高流量账户仍有串行边界；通知基准结果不能代表单聊容量，具体吞吐和尾延迟需要按实际双向聊天负载测量。

服务启动时从当前 schema.sql 初始化 `snap_flow.im_direct_conversation`、`snap_flow.im_direct_message` 及计数字段，不需要旧 IM 数据迁移。主备配置、HTTP 背压和可靠性保证见[多实例部署](distributed.md)。

## 单聊控制与在线隐私

配置 `polyglot-im.direct-chat.enabled=true`，以及固定 HTTPS `directory-url`。仅本机联调允许 HTTP 环回地址。业务目录接收 `POST {user_ids:[...]}`，携带当前用户 Bearer，返回 `data={active:true,app_id,user_id,items:[{user_id,exists,following,follows_you}]}`。following 表示请求者关注对方，follows_you 表示对方关注请求者。目录必须从凭证识别请求者；IM 不缓存授权结果，不记录凭证，不跟随重定向，单批最多50人、响应最多64KiB、超时5秒。

默认接收权限为互相关注 `mutual`；可改为所有人 `all` 或本人关注的人 `following`。创建和每次发送（含幂等重试）均复核权限；已有会话不绕过接收权限。任一方拉黑均阻止双向发送，不清空历史。免打扰仅关闭该用户的新私信系统推送，站内未读仍计数。已排队的推送在派发前再次核对拉黑、免打扰、删除和撤回状态；已交给APNs的通知无法撤回。

| 方法与路径 | 语义 |
| --- | --- |
| GET /direct/settings | 本人的 inbound_scope、online_visibility 与 read_receipts_visible |
| PUT /direct/settings | 更新以上两项；在线可见范围 mutual 或 off |
| GET、PUT /peers/{peer}/preferences | 本人 blocked、muted、pinned；查询另返回当前 can_send |
| GET /peers?blocked_only=true | 按 peer_user_id 分页列出本人偏好；支持 after、limit |
| POST /presence/query | 数组 user_ids 查询在线状态，最多50人；GET /presence?user_ids=... 为逗号分隔替代形式 |
| PUT /presence | device_id、online；仅本人有效设备，60秒在线租约 |
| DELETE /presence/{device} | 本人设备下线 |
| PUT /conversations/{id}/visibility | up_to_seq、hidden、clear；边界不得超过当前 latest_seq |
| DELETE /conversations/{id}?up_to_seq=... | 清除本人截至边界的历史并隐藏会话 |
| DELETE /conversations/{id}/messages/{message} | 本人视角删除，不影响对方 |
| PUT /conversations/{id}/messages/{message}/recall | 发送者在数据库时间120秒内撤回；重复撤回幂等 |

各路径均带 `/api/v1/im` 前缀，仅用户角色可用；未开启控制时返回503。只返回本人的拉黑、免打扰、置顶，不暴露对方的拉黑记录。`can_send=false` 只表示当前无法发送，不告知是否被拉黑。会话偏好经可靠同步跨设备更新；账号级隐私设置通过查询读取，App在前台每25秒及恢复前台时刷新。

在线状态由前台设备每25秒续约。仅双方互关、均公开在线且未双向拉黑时可见；任一有效设备租约未到期即 online，否则 offline。其余情况为 hidden，不能推断为离线。状态只存在客户端内存，不进入可靠消息缓存；关闭、登出及后台停续约，断网由60秒租约兜底。

## 删除、撤回与缓存兼容

协议仍为 schema_version=1；新增字段均为可选扩展。会话增加本人 `blocked/muted/pinned/hidden/clear_seq`。单聊消息 `state_version` 缺省0；`chat.recalled` 仅保留 sender_id，不再带正文。四语言缓存按消息状态版本合并，个人删除墓碑不会被旧历史覆盖；clear_seq及之前消息从本人缓存移除，迟到历史页也不能恢复。旧客户端能忽略新增字段，但不能正确呈现撤回提示，启用前应升级全部用户端。

清空和删除只影响本人各设备，不推进对方可见的已读回执；确认前固定 up_to_seq，确认期间到达的新消息不被清除。隐藏在新消息序号超过隐藏边界时自动解除。个人删除、清空和撤回均重算未读；不能用序号相减代替未读数。会话列表服务端按ID分页，App读取缓存后按置顶、最后消息时间排序。

## 现有部署启用

首次初始化包含控制表。已有数据库需由运维明确执行 [direct-controls.sql](../scala-server/src/main/resources/db/direct-controls.sql) 并核对结构，再配置业务目录、开启控制、部署各客户端；不自动变更已有结构。启用会写入持久激活标记，之后禁止关闭控制以绕过权限。回滚须保留权限校验能力。扩展不提供编辑、输入状态、媒体私信或群聊。

### 已读状态隐私

`GET/PUT /direct/settings` 的 `read_receipts_visible` 为布尔值，默认 `true`；PUT 省略此字段保留原值，旧客户端更新接收范围或在线状态不会重置该选择。Scala 使用可选 `readReceiptsVisible`，Python 使用 `read_receipts_visible`，Dart/uni-app 使用可选 `readReceiptsVisible`。

关闭后仍记录本人实际阅读水位，正常清除未读和角标；对方收到的会话 `peer_read_visible=false`、`peer_read_seq="0"`。历史、快照及保留事件重放均按当前隐私过滤。变更为已有会话生成更高版本事件，在线客户端同步后移除旧回执；已经被对方看过的信息无法收回，离线缓存需重新同步后更新。重新开启公开当前累计已读位置。此开关只控制本人的回执，不影响查看对方主动公开的回执。

已有部署须先备份并显式执行 [read-receipts.sql](../scala-server/src/main/resources/db/read-receipts.sql)，再更新 Server；缺列时 readiness 和启动检查拒绝就绪。回退应用保留新增列及隐私值；旧 Server 不支持该隐私规则，不能在关闭设置仍生效时直接回退旧 Server。新 SDK 对旧 Server 响应缺少 `peer_read_visible` 按原回执语义处理，旧 SDK 仍可通过被过滤为 0 的水位正确隐藏已读。
