# Polyglot IM Dart Client

纯 Dart HTTP/WebSocket 通知 Client，提供消息模型、通知发送、增量同步、历史分页、已读及推送偏好。HTTP、凭证和本地存储均可注入，核心不依赖 Flutter。

## 使用示例

Dart 3.12+。宿主提供用户 Token、安装 ID 和实现 `LocalStore` 的缓存：

```dart
import 'package:polyglot_im_dart_client/polyglot_im_dart_client.dart';

Future<void> syncMessages({
  required String baseUrl,
  required String appId,
  required String userId,
  required String deviceId,
  required Future<String> Function() readUserToken,
  required LocalStore store,
}) async {
  final client = ImClient(
    api: HttpApi(baseUrl: baseUrl, tokenProvider: readUserToken),
    scope: AccountScope(environment: baseUrl, appId: appId, userId: userId),
    deviceId: deviceId,
    store: store,
  );
  try {
    await client.initialize();
    await client.sync();
    final page = await client.history();
    print(page['items']);
  } finally {
    await client.close(logout: false);
  }
}
```

Flutter App 可直接使用 Flutter Client，它导出 Dart Client 的公共能力并提供 SQLite 缓存。`VolatileStore` 适合测试或明确接受重启后重新同步的场景。

## 运行例子与测试

先在仓库根目录执行 `just demo-server`，再执行：

```sh
cd examples/dart-client
dart run live.dart
```

[真实网络示例](../examples/dart-client/README.md) · [Flutter / Dart 集成](../docs/client/flutter.md) · [返回 Polyglot IM 项目首页](../README.md)

## 用户单聊

支持文字与 Unicode 表情、唯一双人会话、可靠同步、历史分页和已读回执。完整接口与使用例子见[单聊集成](../docs/direct-chat.md)。图片与 COS 接入暂不支持。

HTTP 使用 `code/message/data` 通用信封，Client 自动解包；服务端公共前缀由 http4s Router 统一挂载。完整约定见[HTTP 协议](../docs/protocol.md)。

## 分层与替换适配器

业务同步位于 service/SyncClient，模型与缓存归并位于 domain，接口位于 ports，默认 HTTP/WS/JSON 实现位于 adapters。ImClient 是提供默认适配器的便捷入口，原 http.Client 与 socketFactory 参数仍可使用。

```dart
ImClient assemble({required RemoteApi remote, required AccountScope scope,
  required String deviceId, required LocalStore store,
  required SocketConnector socket, required WireCodec codec}) => ImClient(
    api: remote, scope: scope, deviceId: deviceId, store: store,
    ownsApi: false, socketConnector: socket, codec: codec,
  );
```

HttpApi 还可接收 transport、codec 和 retryPolicy。注入的 transport 由宿主关闭；同一链路的 API 与 Socket 使用相同 codec。完整代码见[装配示例](../examples/dart-client/pluggable.dart)，约定见[分层与扩展](../docs/extensibility.md#分层与扩展入口)。
