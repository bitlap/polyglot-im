# 用户单聊：文字与表情

单聊支持 Scala、Python、Dart，以及 Flutter 的会话列表和聊天页。图片、贴图、COS 上传与资源下载暂不支持。Unicode 表情直接包含在文本中，不需要独立上传或附件消息。

## 身份与路由

客户端持有本人 User Token，以 `peer_user_id` 创建会话。同一应用的一对不同用户，无论谁先创建、重试或落在哪个实例，均返回同一个 `conversation_id`。消息发送、读取和标记已读均在服务端校验成员；非成员及跨应用访问返回 404。`payload.sender_id` 由服务端从令牌生成，客户端只传 `payload.text`，不能伪造发送者，也不能调用服务通知接口。

该默认模式允许应用内已认证用户按用户 ID 发起单聊，不检查业务用户目录、好友、拉黑或内容审核。需要这些策略的业务应在服务端接入身份及关系校验；不要将客户端页面隐藏当作权限限制。

发送请求可到任意实例。消息、会话序号、双方持久事件、未读计数及接收方设备的 APNs Outbox 在同一 PostgreSQL 事务提交。接收者连接的实例通过 LISTEN/NOTIFY 唤醒，再读持久事件流；漏掉唤醒时仍可通过定期协调和 HTTP 补拉恢复。通知和单聊共用账户游标与 WebSocket，分别保存消息、序号和已读状态。

## HTTP 接口

所有路径以 `/api/v1/im` 开头，要求用户身份；服务身份不能冒充用户发送单聊。

| 方法与路径 | 输入 | 返回 |
| --- | --- | --- |
| POST /conversations/direct | peer_user_id | 会话状态；不允许自己与自己单聊 |
| GET /conversations | after、limit | items、has_more、next_after；按会话 ID 排序 |
| POST /conversations/{id}/messages | client_message_id、type=chat.text、payload={text} | 新消息 201；相同幂等重试 200 |
| GET /conversations/{id}/messages | before_seq、limit | items、has_more、next_before_seq、conversation |
| PUT /conversations/{id}/read | up_to_seq | 更新后的会话状态 |
| GET /messages/{id} | 消息 ID | 鉴权获取通知或单聊消息，供推送点击使用 |

文本为 1～2048 个 Unicode 码点，支持换行、中文、组合表情及肤色修饰符；拒绝全空白和 NUL。发送载荷只能包含 text；图片类型、附件和客户端传入的 sender_id 被拒绝。长度不是屏幕上可见字符的数量，一个组合表情可能包含多个码点。

幂等键作用域为应用、用户、client_message_id，跨会话生效；相同键更改目标或内容返回 409。超时和应用重试必须保留原键和原文。SDK 不自动将用户草稿和待发送队列持久化；宿主可用本地数据库保存发送意图。Flutter 组件在页面存活期间保留失败消息的键，原文重试不重复创建消息。

## 状态、同步与缓存

每个会话包含 conversation_id、kind=direct、member_ids、revision、latest_seq、read_seq、peer_read_seq、unread_count 和 messages。数字均为十进制字符串。unread_count 只统计对方发来且尚未读的消息，不能简单用 latest_seq-read_seq 计算。对方已读水位覆盖本方消息序号时，聊天页显示“已读”；发送响应仅表示服务器持久化成功，不代表对方设备已接收。

`conversation.updated` 事件的 data 是接收者视角的会话状态及新增消息数组；创建、发送和已读更新均通知双方。会话 revision 单调递增；三端缓存拒绝旧历史响应覆盖较新的已读状态，同时合并历史消息。单聊消息保存在 `cache.conversations[conversation_id].messages`，通知仍保存在 `cache.messages`。缓存与账户游标原子保存后才确认接收。

首次快照或游标过期重建时，仅带至多 100 个会话摘要，不包含单聊历史；conversations_has_more 表示尚有会话，使用会话分页补齐。打开会话后调用 conversationHistory 获取历史，页面向前分页加载。重建缓存会清空旧会话消息，服务器历史仍可重新读取。HTTP 历史、创建和已读响应不推进账户游标，避免跳过其他消息事件。

全局状态中的 unread_count 仍仅指通知，chat_unread_count 是所有单聊未读总数，不受会话列表分页影响。iOS 角标使用两者之和，限制为 9999。单聊推送复用设备静音、预览开关、租约和重试机制；已读、登出或解绑会抑制尚未投递的任务，已交给 Apple 的通知无法撤回。APNs 真机验收仍需部署账号与设备。

## SDK 示例

以下 client 均为完成 initialize 的本人客户端；构建和登录方式见各 SDK README。

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

Flutter 在页面中使用 `ImDirectChat(client: client, conversationId: id)`；会话列表使用 `ImConversations(client: client, onOpen: openChat)`。宿主启动实时同步，页面只在显示聊天时上报已显示的阅读水位。请将不同账号绑定到不同的 ImClient，并在退出时释放旧页面及客户端。

## 本机双人演示

根目录运行 `just demo-server`，临时 PostgreSQL 和测试令牌写入被 Git 忽略的 .local-demo。Alice 使用 flutter.json，Bob 使用 flutter-bob.json；服务凭证只在命令行示例中使用，不写入移动端配置。

```sh
cd examples/flutter-sdk
flutter run -d <alice-device> --dart-define-from-file=../../.local-demo/flutter.json
flutter run -d <bob-device> --dart-define-from-file=../../.local-demo/flutter-bob.json
```

进入“单聊”，点击发起单聊并输入对方用户 ID。两个模拟器可使用各自设备的 SQLite 缓存；真机需要将本机回环地址替换为可达的开发地址，并按 iOS 网络策略配置测试环境。退出 demo-server 后临时数据库被释放，下一次演示应使用新生成的配置。

## 分布式边界

单聊写入先获取幂等事务锁，再锁会话行，最后按相同的用户 ID 顺序锁双方账户状态行；其他控制事务不反向获取会话锁。每条会话内消息及双方游标按提交顺序分配，重复请求不增加序号或未读。发送路径对未读计数作事务增量更新，已读时只统计已覆盖区间，避免每次发送扫描全部未读历史。同一用户多会话共享账户游标锁，因此超高流量账户仍有串行边界；通知基准结果不能代表单聊容量，具体吞吐和尾延迟需要按实际双向聊天负载测量。

数据库 V3 为新增表和计数字段，服务启动时自动迁移；升级前按部署流程备份。先完成所有实例及 Worker 升级，再向客户端开放单聊入口，避免旧 Worker 消费其无法识别的新消息任务。主备配置、HTTP 背压和可靠性保证见[多实例部署](distributed.md)。
