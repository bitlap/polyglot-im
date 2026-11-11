# Polyglot IM Dart SDK

纯 Dart HTTP/WebSocket 通知 SDK，提供消息模型、通知发送、增量同步、历史分页、已读及推送偏好。HTTP、凭证和本地存储均可注入，核心不依赖 Flutter。

## 使用示例

Dart 3.12+。宿主提供用户 Token、安装 ID 和实现 `LocalStore` 的缓存：

```dart
import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

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

Flutter App 可直接使用 Flutter SDK，它导出 Dart SDK 的公共能力并提供 SQLite 缓存。`VolatileStore` 适合测试或明确接受重启后重新同步的场景。

## 运行例子与测试

先在仓库根目录执行 `just demo-server`，再执行：

```sh
cd examples/dart-sdk
dart run live.dart
```

[真实网络示例](../examples/dart-sdk/README.md) · [Flutter / Dart 集成](../docs/sdk/flutter.md) · [返回 Polyglot IM 项目首页](../README.md)
