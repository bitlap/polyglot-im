# SDK 集成总览

## 当前可用能力

Scala、Dart、Python 当前只提供消息信封编解码；Flutter 额外提供生命周期恢复回调。没有已发布版本、网络客户端、发送队列、本地消息库或系统推送插件。

- [Scala 集成](scala.md)。
- [Flutter / Dart 集成](flutter.md)。
- [Python 集成](python.md)。

使用文档前先检查[功能状态](../features.md)。各页“当前用法”为真实代码；“目标 API”为尚未实现的设计，不能直接复制调用。

## 首批 SDK 交付范围（规划）

M1 优先交付 Scala/Python 服务端通知发送、HTTP 历史/同步/已读契约，以及 Flutter App 的消息列表、未读、持久缓存和身份隔离；M2 增加实时接收与目标平台推送。Scala/Python 通用长连接订阅、用户聊天发送 UI、群消息和撤回不阻塞首批 App 集成。完整目标 API 保留在下文，逐阶段实现，不一次性开放空接口。

## 宿主职责

业务宿主提供用户身份、短期令牌、设备 ID、资源导航、产品通知策略与存储隔离参数。服务端凭证不能进入 Flutter/Web 包。消息 SDK 不注册一套独立业务用户体系。

## 目标客户端契约（规划）

| 操作 | 语义 |
| --- | --- |
| connect(tokenProvider, deviceId) | 身份恢复、版本协商、追增量后进入在线态 |
| disconnect() | 取消连接与重试，不隐式删除用户历史 |
| sendMessage(conversationId, payload, clientMessageId) | 保存发送意图，服务端确认后返回正式身份 |
| watchMessages(conversationId) | 订阅本地持久化后的消息视图 |
| watchConversations() | 订阅会话与未读变化 |
| watchConnectionState() | idle / connecting / syncing / online / retrying / authRequired |
| markRead(conversationId, upToSeq) | 只提交用户实际看到的最大位置 |
| sync() | 合并并发请求，可靠补拉至提交水位 |

Dart 使用 Future/Stream；Scala 使用异步结果及可选效果系统适配；Python 使用 async/await 和异步迭代器。不是三份完全相同的同步 API，也不要求服务端脚本持有永久长连接。

## 目标错误与生命周期

重试受错误分类驱动；401 只合并刷新一次，403/409 不盲重试，429 尊重重试时间；发送超时复用幂等键。宿主主动退出登录时停止所有任务，解绑推送并清理凭证。消息和游标必须一起提交，页面读取本地视图，不从原始 WS 回调直接拼接 UI。
