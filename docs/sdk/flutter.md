# Flutter / Dart SDK 集成

Flutter App 只声明 `polyglot_im_flutter_sdk`，它重导出 Dart 核心；纯 Dart 工程只依赖 `polyglot_im_dart_sdk`。Flutter 3.44+、Dart 3.12+；iOS 推送首批仅 APNs。

```yaml
dependencies:
  polyglot_im_flutter_sdk:
    path: ../polyglot-im/flutter-sdk
```

## 初始化与消息页

```dart
import 'package:polyglot_im_flutter_sdk/polyglot_im_flutter_sdk.dart';

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
ImInbox(client: client, onOpen: openBusinessResource);
```

订阅 `client.changes` 更新红点，`client.unreadCount` 是 BigInt；不要转换成 JS 不安全整数。`messages` 按会话序号倒序。`ImInbox` 包含分页、刷新、空态、错误重试、阅读状态和全部已读。进入消息页不会自动把尚未看见的消息设为已读；调用 `markRead(实际可见序号)`，或让用户点击“全部已读”。默认捕获当时的 latest_seq，不覆盖后来消息。

用户点击时 `getMessage(id)` 验证消息归属；再调用业务 API 验证 action 对应资源权限，最后受控导航。未知 action 展示普通详情。不可直接打开任意 URL。账号或环境变化必须关闭旧 client，使用新 scope；退出先解绑设备，再清除宿主登录凭证。

## APNs

```dart
final push = IosPushBinding(
  client: client,
  onOpen: (message) { /* 安全导航，宿主已有登录身份 */ },
  onError: (error, stack) { /* 展示可重试错误，勿记录令牌 */ },
);
await push.attach();
// 用户主动点击“开启通知”后：
final allowed = await push.requestPermission();
// 用户偏好：muted/preview 按设备保存；preview 默认关闭。
await client.setPush(apnsToken, muted: true, preview: false);
```

绑定在 client.initialize 完成后执行，并保持到退出登录。attach 读取待处理的冷启动通知；不匹配当前 app/user 的通知不会打开。前台收到推送时只补拉，不额外展示同一系统横幅。点击按 message_id 去重；服务端已读状态才是红点依据，APNs accepted 不是已读。

Xcode 宿主启用 Push Notifications capability，选择自己的 Team、Bundle ID、开发/发布 provisioning；服务端 APNs topic 必须等于 Bundle ID。`AppDelegate` 继承 FlutterAppDelegate，并在 didFinishLaunching 设置：

```swift
UNUserNotificationCenter.current().delegate = self
```

保留 `GeneratedPluginRegistrant` 注册和 super 生命周期转发。插件同时注册 App 与 Scene 生命周期，接收 iOS Scene 冷启动 notificationResponse。若已有其他 UNUserNotificationCenter delegate，宿主应明确多播这些回调并确保 completion 只执行一次。原生桥接采用 Flutter MethodChannel/EventChannel，提供权限请求、token 更新、通知事件及角标。SwiftPM 与 CocoaPods 均有配置。

不配置 Apple 凭证也能运行站内消息和模拟器测试。真实远程推送须另外用自己的开发者账号与真机验收，见[验证记录](../verification.md)。系统推送通道为 iOS APNs。

## 生命周期和释放

`IosPushBinding` 恢复前台时补拉并恢复 WS，退后台暂停 WS。拒绝权限不影响 HTTP/WS；轮询由宿主按需启动。只有 iOS 创建该 Binding，其他平台使用 `ImLifecycleObserver` 的 onResume 调用 sync/startRealtime。

```dart
await push.dispose();
await client.close(logout: true, clearCache: false);
await store.close();
```

HttpApi 默认拥有其网络客户端；注入外部 HTTP client 时由宿主关闭。Web / 纯 Dart 必须注入适合平台的 LocalStore；显式使用 VolatileStore 时进程关闭即丢缓存，重启通过服务端快照恢复最新 100 条，不能宣称离线持久化。Flutter SQLite 适配支持 iOS；其他平台不隐式退到内存。

## 可运行 iOS 工程

```sh
just demo-server                # 终端一，临时 PostgreSQL，写本地测试配置
just test-live                  # 终端二，Python/Dart 实际 HTTP/WS 示例
just example-ios                # 本地模拟器，配置只含用户令牌
```

工程位于 [examples/flutter-sdk](../../examples/flutter-sdk/README.md)，App 不包含 service_token。正式 App 用自己的登录 TokenProvider 替换演示的编译期临时配置，禁止把生产令牌编译到安装包。

## 用户文字单聊

会话创建、文字与 Unicode 表情发送、历史和已读回执的完整例子见[单聊集成](../direct-chat.md)。图片与 COS 暂不接入。
