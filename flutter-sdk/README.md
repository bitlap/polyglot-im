# Polyglot IM Flutter SDK

Flutter 消息 SDK 的单一入口，导出 Dart 协议与 HTTP/WebSocket 客户端，并提供 SQLite 持久缓存、ImInbox 消息页、生命周期恢复及 iOS APNs 原生绑定。

## 使用示例

Flutter 3.44+。工程依赖本地 SDK 时使用：

```yaml
dependencies:
  polyglot_im_flutter_sdk:
    path: ../flutter-sdk
```

在已登录的宿主页面中创建客户端，`baseUrl`、`appId`、`userId` 来自宿主配置，`readUserToken` 返回登录后的用户 Token：

```dart
import 'package:polyglot_im_flutter_sdk/polyglot_im_flutter_sdk.dart';

final store = await SqliteStore.openDefault();
final client = ImClient(
  api: HttpApi(baseUrl: baseUrl, tokenProvider: readUserToken),
  scope: AccountScope(environment: baseUrl, appId: appId, userId: userId),
  deviceId: await store.installationId(),
  store: store,
);
await client.initialize();
client.startPolling();

final inbox = ImInbox(
  client: client,
  onOpen: openBusinessResource,
);
```

`openBusinessResource` 接收 `ImMessage`，先向业务 API 校验资源权限再导航。退出时取消页面订阅、关闭客户端，再关闭 `store`。账户和服务环境切换时使用各自的缓存命名空间。

iOS 推送使用 `IosPushBinding`，在客户端初始化后 `attach()`，退出时 `detach()`；需要宿主配置 Apple Team、Bundle ID 与推送能力。CocoaPods 和 SwiftPM 均支持，完整生命周期及平台配置见[集成文档](../docs/sdk/flutter.md)。

## 运行 iOS 示例

在仓库根目录依次运行：

```sh
just demo-server       # 终端一
just test-live         # 终端二：发送演示通知
just example-ios      # 终端二：启动消息中心
```

测试命令为 `just test-flutter`。

[iOS 示例工程](../examples/flutter-sdk/README.md) · [返回 Polyglot IM 项目首页](../README.md)

## 用户单聊

支持文字与 Unicode 表情、唯一双人会话、可靠同步、历史分页和已读回执。完整接口与使用例子见[单聊集成](../docs/direct-chat.md)。图片与 COS 接入暂不支持。

HTTP 使用 `code/message/data` 通用信封，SDK 自动解包；服务端公共前缀由 http4s Router 统一挂载。完整约定见[HTTP 协议](../docs/protocol.md)。
