# uni-app Client

`@bitlap/polyglot-im` 提供与 Scala/Python/Dart 当前用户 SDK 对齐的通知、文字/Unicode 单聊、会话分页、已读回执、HTTP/WS 同步及设备偏好接口。没有内置页面。Flutter 的消息页组件、SQLite 和原生 APNs 桥接属于平台层，不是小程序内的实现。

## 安装与初始化

源码本地依赖：`"@bitlap/polyglot-im": "file:../polyglot-im/uniapp-client"`。可用 `just build-uniapp` 生成包含 LICENSE/NOTICE 的 npm tgz，再执行 `npm install /绝对路径/bitlap-polyglot-im-0.1.1.tgz`。构建产物未上传 registry。SDK 本身不依赖 Node；仓库测试和真实网络示例使用 Node 22。

```js
import { ImClient } from '@bitlap/polyglot-im'

const client = new ImClient({
  baseUrl: config.base_url,
  environment: businessBaseUrl,
  appId: config.app_id,
  userId: currentUserId,
  deviceId: installationId,
  tokenProvider: async () => sessionForExpectedUser(currentUserId),
  refreshToken: async () => refreshSameUserSession(currentUserId),
  onChange: value => renderCommittedState(value.cache),
  onState: state => renderConnectionState(state),
  onError: error => reportMessageError(error),
})
await client.initialize({ realtime: false })
// 确认宿主仍在前台后启动，避免初始化结束时已经进入后台。
client.startRealtime()
client.startPolling()
```

示例中的 config 来自业务后端；登录、身份检查、页面渲染及 installationId 由宿主提供。tokenProvider 每次读取当前业务 Bearer，可使用不透明会话或 JWT，服务端通过对应 UserAuthenticator 校验；账号或业务环境变化必须关闭旧实例，不能让旧实例使用另一个用户的新凭证。预期身份头由 SDK 绑定。并发 401 最多合并刷新一次；新请求可以再次刷新，刷新失败交宿主登录。

`initialize()` 默认启动实时同步。微信部署需配置 IM 的 HTTPS request 合法域名和 WSS socket 合法域名，网关支持 Upgrade，并禁止记录 ticket 查询参数。Bearer 不进入 WS URL；WS 使用会话接口取得的短期单次 ticket。HTTP API 仅接受当前 IM 地址下的相对路径；默认 uni.request 不额外实现重定向拦截，重定向行为由运行平台决定。部署应使用固定且直接响应的 IM HTTPS 地址，网关不将携带凭证的请求重定向到其他源；需要客户端强制拦截重定向的宿主须注入支持该策略的传输。核心 `SyncClient` 不默认创建平台资源，直接使用它时须注入 transport、storage、socketConnector。

## 公共接口

| 能力 | API 与结果 |
| --- | --- |
| 初始化/补拉 | `initialize({realtime:true})`、`sync()` 返回已提交 cache；重复 sync 合并为一次完整追赶 |
| 通知历史/详情 | `history({beforeSeq,limit:50})` 返回分页；兼容 `history(beforeSeq)`；`getMessage(id)` 返回已校验信封 |
| 通知阅读 | `markRead(upToSeq)`；省略时捕获调用瞬间 latest_seq，不误读之后到达的消息 |
| 创建单聊 | `createDirect(peerUserId)` 返回当前用户视角的会话摘要 |
| 会话与单聊历史 | `conversations({after,limit:50})`、`conversationHistory(id,{beforeSeq,limit:50})`；返回服务器分页对象并合并缓存，分页上限 100 |
| 文字/表情 | `sendText(id,{clientMessageId,text})` 返回消息；成功后同步，重试必须复用原 ID 与内容 |
| 单聊阅读 | `markConversationRead(id,upToSeq)` 返回会话，随后同步；对方从 peer_read_seq 收到已读回执 |
| 推送设备 | `updatePushToken(token)`、`setPush(token,{muted:false,preview:false})`；token 为 null 时解绑；`pushPreferences()` 返回偏好 |
| 实时与轮询 | `startRealtime()` / `pauseRealtime()`；`startPolling(intervalMs=30000)` / `stopPolling()` |
| 退出/清理 | `close({logout:false,clearCache:false})`；主动退出显式传 logout:true；clearCache 要求存储实现 clear |
| 当前状态 | `cache`、通知倒序 `messages`、十进制字符串 `unreadCount` / `chatUnreadCount`、`connectionState`；回调 onChange / onState / onError |
| 可信服务发送 | `new NotificationSender(api).send({recipientId,clientMessageId,type,payload})`；借入 `HttpApi`，不负责关闭它 |

状态值为 idle、syncing、connecting、online、retrying、authRequired；online 表示当前同步或连接成功，不承诺持续网络可用。callback 是状态通知，不是恰好一次业务消费。消息跳转由宿主白名单解释 payload.action，先 getMessage 验证归属，禁止执行任意 URL。

## 一致性、性能与资源

uni-app 默认提供本地 uni 持久存储，不提供 PostgreSQL 适配器；小程序不配置数据库凭证。与其他客户端的存储能力和资源约定见[客户端存储](storage.md)。

- 所有 ID、序号、revision 和未读数使用字符串；十进制比较与减法不依赖 BigInt，保留超过 JavaScript 安全整数范围的数据。未知 type/payload 原样保存。
- `cache.messages` 是通知；`cache.conversations[id].messages` 是单聊消息映射。会话摘要按 revision 合并，旧历史只能补消息；历史分页不推进同步 cursor。通知 latest_seq/read_seq 不回退，通知和单聊未读分开。
- 整个账号缓存与 cursor 串行保存成功后才发布并 ack；缓存键同时包含业务环境、IM 地址、appId、userId。不再静默截断为 500 条。默认 uni 存储整体保存，历史累积会增加序列化成本且受平台配额限制；大历史宿主应注入具备事务语义的数据库存储并按需分页。配额失败向上传播、不推进内存游标、不 ack，不以内存降级。
- 实时连接单实例只有一个；帧串行处理，待处理上限 32 帧，超过则断开并 HTTP 补拉；重连从 250ms 指数退避到 30 秒并加抖动，连接建立超时 10 秒。服务器发送协议 ping，平台 WebSocket 处理 pong；SDK 不依赖后台长连接保证投递。
- HTTP 单请求超时由传输端口执行（默认 10 秒）。网络错误、429、500/502/503/504 默认重试至多 3 次，401 刷新至多一次；Retry-After 秒数最多等待 30 秒，403/409 不自动重试。请求体在重试前固定，客户端没有持久待发送队列。
- 前台轮询默认 30 秒，失败退避至 120 秒；后台同时 stopPolling 和 pauseRealtime，恢复先 sync 再订阅。close 取消默认 HTTP 请求、重试等待、轮询和重连，旧响应不能再提交；已经在存储中执行的写入在关闭完成前收尾。借入 transport 默认不关闭，显式 ownsTransport:true 才调用 transport.close。
- close 默认 logout:false；主动退出必须显式传 logout:true。注销失败仍释放本地资源，业务端应撤销会话并将解绑失败作为可重试操作处理。账号凭证不进入缓存。

可替换端口：HTTP `transport(options) -> Promise<{statusCode,data,header}>`；存储 `load/save/clear`；Socket `socketConnector({url,onOpen,onMessage,onClose,onError}) -> {send,close}`；codec `encode/decode`；凭证 `tokenProvider/refreshToken`；重试 `RetryPolicy`。契约见 [contracts.js](../../uniapp-client/src/ports/contracts.js)，默认适配器见 [uni.js](../../uniapp-client/src/adapters/uni.js)。注入适配器必须保留取消、原子存储、凭证隔离和资源所有权语义。

`HttpApi` 可独立提供上述 HTTP 认证/重试能力，用户使用时同时指定 appId/userId，可信服务端使用服务凭证时省略用户绑定。服务凭证和事务 Outbox 属于业务后端；不把 Scala/Python 的数据库 Outbox 搬入小程序。

## 平台边界与验证

SDK 的设备 API 仅管理服务端 token/偏好，不能自行申请 iOS 权限或生成 APNs token。微信小程序不能通过该 SDK 接收原生 APNs；微信订阅消息尚未接入。原生 iOS 使用 Flutter/原生宿主桥接，APNs 真机投递仍单独验收。sf-uniapp 当前消息页展示业务通知，并未新增单聊 UI；SDK 单聊能力可供宿主后续页面直接调用。各端均未提供的群聊、媒体、持久发送队列等能力不因本次对齐而成为已实现。

运行 `just test-uniapp`；`just test-integration` 会构建 tgz、安装到临时独立工程，再运行 [真实服务示例](../../examples/uniapp-client/live.mjs)，覆盖真实 HTTP/WS、单聊表情、幂等、通知、阅读、重连和持久化。Node 适配器仅用于示例，包内源码不依赖 Node。
