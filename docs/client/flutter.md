# Flutter Client 集成

Flutter App 只声明 `polyglot_im_flutter`，它重导出 Dart 核心；纯 Dart 工程只依赖 `polyglot_im`。Flutter 3.44+、Dart 3.12+；原生推送仅提供 iOS APNs 适配。

```yaml
dependencies:
  polyglot_im_flutter:
    path: ../polyglot-im/flutter-client
```

## 初始化与消息页

```dart
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

final store = await SqliteStore.openDefault();
final client = ImClient(
  api: HttpApi(baseUrl: baseUrl, tokenProvider: () async => currentAccessToken),
  scope: AccountScope(environment: baseUrl, appId: appId, userId: userId),
  deviceId: await store.installationId(),
  store: store,
);
await client.initialize();
client.startPolling(); // WS 不可用时仍定期补拉
// Widget 内：
ImInbox(client: client, onOpen: openBusinessResource, markReadOnOpen: false);
```

订阅 `client.changes` 更新红点；`client.unreadCount` 为 BigInt，`messages` 按会话序号倒序。ImInbox 提供分页、刷新、空态、错误重试、单条已读、左滑已读/删除、选择模式下的批量已读/删除和全部已读；进入页面不自动标记已读。

默认在 onOpen 成功返回后，只标记当前消息已读，不影响更早的通知。设 `markReadOnOpen: false` 可由宿主在确认详情已显示后调用 `markMessagesRead([message.messageId])`。“全部已读”或省略参数调用 markRead() 时，捕获调用瞬间的 latest_seq，不覆盖后来消息。

ImInbox 将消息交给宿主 onOpen；宿主应调用 `getMessage(id)` 验证消息归属，再调用业务 API 检查资源权限，最后受控导航。APNs 点击由 IosPushBinding 先获取消息，再交给 onOpen。未知 action 展示普通详情。不可直接打开任意 URL。账号或环境变化必须关闭旧 client，使用新 scope；退出先解绑设备，再清除宿主登录凭证。

## APNs

```dart
final push = IosPushBinding(
  client: client,
  foregroundNotifications: true, // 显式启用当前账户的前台系统横幅
  onOpen: (message) { /* 安全导航，宿主已有登录身份 */ },
  onError: (error, stack) { /* 展示可重试错误，勿记录令牌 */ },
);
await push.attach();
// 用户主动点击“开启通知”后：
final allowed = await push.requestPermission();
// 用户偏好：muted/preview 按设备保存；preview 默认关闭。
await client.setPush(apnsToken, muted: true, preview: false);
```

绑定在 client.initialize 完成后执行，并保持到退出登录。attach 读取待处理的冷启动通知；不匹配当前 app/user 的通知不会打开。前台收到推送时补拉；foregroundNotifications 默认关闭，显式开启时由 iOS 展示当前绑定账号的系统横幅，不再由 WS 生成本地通知。静音切换通过 ForegroundPushPlatform.configureForeground 更新策略。点击按 message_id 去重；服务端已读状态才是红点依据，APNs accepted 不是已读。

Xcode 宿主启用 Push Notifications capability，选择自己的 Team、Bundle ID、开发/发布 provisioning；服务端 APNs topic 必须等于 Bundle ID。`AppDelegate` 继承 FlutterAppDelegate，并在 didFinishLaunching 设置：

```swift
UNUserNotificationCenter.current().delegate = self
```

保留 `GeneratedPluginRegistrant` 注册和 super 生命周期转发。插件同时注册 App 与 Scene 生命周期，接收 iOS Scene 冷启动 notificationResponse。若已有其他 UNUserNotificationCenter delegate，宿主应明确多播这些回调并确保 completion 只执行一次。原生桥接采用 Flutter MethodChannel/EventChannel，提供权限请求、token 更新、通知事件及角标。SwiftPM 与 CocoaPods 均有配置。

不配置 Apple 凭证也能运行站内消息和模拟器测试。真实远程推送须另外用自己的开发者账号与真机验收，见[验证记录](../verification.md)。系统推送通道为 iOS APNs。

## 生命周期和释放

Flutter 使用本地 SQLite，不提供 PostgreSQL 存储入口，也不配置数据库凭证。Python/Scala/Dart 可信宿主的 PostgreSQL 可选缓存与本端分开，完整能力矩阵见[客户端存储](storage.md)。

`IosPushBinding` 恢复前台时先补拉，再启动 WS 与兜底轮询；进入 paused 时停止两者。首次初始化后的轮询由宿主启动。拒绝通知权限不影响站内 HTTP/WS。只有 iOS 创建该 Binding，其他平台使用 `ImLifecycleObserver` 的 onResume 调用 sync/startRealtime。

```dart
await push.dispose();
await client.close(logout: true, clearCache: false);
await store.close();
```

HttpApi 默认拥有其网络客户端；注入外部 HTTP client 时由宿主关闭。纯 Dart VM 可通过独立入口注入 SqliteStore，见 [Dart 存储集成](dart.md)；Web 必须注入适合平台的 LocalStore；显式使用 VolatileStore 时进程关闭即丢缓存，重启通过服务端快照恢复最新 100 条，不能宣称离线持久化。Flutter SQLite 适配基于 sqflite，可用于 iOS／Android；当前原生示例与发布验证以 iOS 为准，Android 未做本轮设备验收。其他平台不隐式退到内存。

## 可运行 iOS 工程

```sh
just demo-server                # 终端一，临时 PostgreSQL，写本地测试配置
just test-live                  # 终端二，Scala/Python/Dart/uni-app 实际 HTTP/WS 示例
just example-ios                # 本地模拟器，配置只含用户令牌
```

工程位于 [examples/flutter-client](../../examples/flutter-client/README.md)，App 不包含 service_token。正式 App 用自己的登录 TokenProvider 替换演示的编译期临时配置，禁止把生产令牌编译到安装包。

## 用户文字单聊

会话创建、文字与 Unicode 表情发送、历史和已读回执的完整例子见[单聊集成](../direct-chat.md)。图片与 COS 暂不接入。

通知列表所有类型共用主题卡片布局：信箱图标与单行标题在同一行居中，未读以圆点显示，正文和分钟时间与卡片左边缘对齐，不跟随标题缩进；长标题/正文在列表中省略，完整详情由宿主 onOpen 展示。这是 SDK 通用组件的布局；SnapFlow 自行实现互动／系统列表，作品和用户直接鉴权后导航，只有回复按钮打开底部输入框。SDK 不解释业务审核状态或替宿主决定导航。

## iOS 授权状态与注册恢复

`IosPushBinding.notificationSettings()` 返回系统实际授权与锁屏开关；`notDetermined` 表示尚未申请，`denied` 表示已拒绝，授权成功不等于设备已连接 IM。`registered` 仅在 token 成功提交当前账号后为 true，不代表 APNs 已投递。

宿主在用户操作后调用 `requestPermission()`，返回系统实际授权结果；token 注册或上报失败通过 `onError` 报告，不把已授权改成拒绝或授权异常；已拒绝时可调用 `openNotificationSettings()` 打开本应用通知设置。`refreshRegistration()` 用于注册或上报暂时失败后重试；返回前台也会重新检查授权、注册并绑定，撤销授权时解绑服务端 token。注册失败不阻断站内消息同步。`onRegistrationChanged` 用于刷新宿主界面；token 和错误通过原有事件端口处理。

这些设置能力由可选 `IosPushSettingsPlatform` 定义，默认 `MethodChannelPush` 已实现。自定义平台未实现该端口时，读取或打开设置会显式抛出 `UnsupportedError`；原有权限请求、token 和通知事件接口保持兼容。

宿主通过 `selectionMode` 控制选择模式，通过 `onSelectionDone` 结束操作后的选择模式。每批最多 100 条；全选只选择当前已加载的前 100 条，不代表全信箱。远端失败保留本地消息并提示；成功同步落盘后更新列表和角标。

通知缓存同时应用单条状态和账号水位：`is_read || conversation_seq <= read_seq`。累计已读不会逐条改写服务端 `is_read`，SDK 将水位应用到已有缓存及后来加载的历史；不修改消息版本或单聊状态，水位之后的新通知仍未读。总数使用服务端 `unread_count`，不能用序号差代替。
