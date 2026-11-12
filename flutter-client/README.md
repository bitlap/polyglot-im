# Polyglot IM Flutter Client

Flutter 消息 Client 的单一入口，导出 Dart 协议与 HTTP/WebSocket 客户端，并提供 SQLite 持久缓存、ImInbox 消息页、生命周期恢复及 iOS APNs 原生绑定。

## 使用示例

Flutter 3.44+。工程依赖本地 Client 时使用：

```yaml
dependencies:
  polyglot_im_flutter:
    path: ../flutter-client
```

在已登录的宿主页面中创建客户端，`baseUrl`、`appId`、`userId` 来自宿主配置，`readUserToken` 返回登录后的用户 Token：

```dart
import 'package:polyglot_im_flutter/polyglot_im_flutter.dart';

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

iOS 推送使用 `IosPushBinding`，在客户端初始化后 `attach()`，退出时 `detach()`；需要宿主配置 Apple Team、Bundle ID 与推送能力。CocoaPods 和 SwiftPM 均支持，完整生命周期及平台配置见[集成文档](../docs/client/flutter.md)。

## 运行 iOS 示例

在仓库根目录依次运行：

```sh
just demo-server       # 终端一
just test-live         # 终端二：发送演示通知
just example-ios      # 终端二：启动消息中心
```

测试命令为 `just test-flutter`。

[iOS 示例工程](../examples/flutter-client/README.md) · [返回 Polyglot IM 项目首页](../README.md)

## 用户单聊

支持文字与 Unicode 表情、唯一双人会话、可靠同步、历史分页和已读回执。完整接口与使用例子见[单聊集成](../docs/direct-chat.md)。图片与 COS 接入暂不支持。

HTTP 使用 `code/message/data` 通用信封，Client 自动解包；服务端公共前缀由 http4s Router 统一挂载。完整约定见[HTTP 协议](../docs/protocol.md)。

## 分层与替换平台能力

presentation 保存页面，service 保存推送绑定与生命周期编排，ports 定义 IosPushPlatform；SQLite 与 MethodChannelPush 分别位于 adapters/storage 和 adapters/push。页面接收 Dart SyncClient，原 ImClient 可直接传入。

```dart
IosPushBinding bindPush(SyncClient client, IosPushPlatform platform,
    void Function(ImMessage) onOpen,
    void Function(Object, StackTrace) onError) => IosPushBinding(
  client: client, platform: platform, onOpen: onOpen, onError: onError,
);
```

不传 platform 时使用原 iOS MethodChannel 实现；替代实现须保持账户隔离、实际权限结果与取消订阅语义。传输与本地缓存通过 Dart 端口注入，详见[分层与扩展](../docs/extensibility.md#分层与扩展入口)。
