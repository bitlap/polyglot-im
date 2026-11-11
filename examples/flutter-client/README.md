# Polyglot IM iOS 示例

只依赖 Flutter Client 单一入口，演示真实 HTTP/WS、SQLite、未读/已读、列表分页、APNs 绑定和生命周期恢复。

1. 根目录运行 `just demo-server`，临时 PostgreSQL 与随机本机 HTTP 端口启动后写 `.local-demo/config.json` 与仅用户凭证的 `flutter.json`。
2. 另一终端运行 `just test-live`，发送 Scala 服务可持久化的 Python/Dart 通知。
3. 根目录运行 `just example-ios`，或本目录执行 `flutter run --dart-define-from-file=../../.local-demo/flutter.json`。

本机模拟器可以访问 127.0.0.1；真机需显式配置可访问的测试服务 HTTPS 地址。示例不把 service_token 带进 App；编译期 user_token 仅供本地临时演示，真实 App 使用登录后的 TokenProvider。

Xcode Team/Bundle ID 和 Push Notifications capability 由集成者填写。APNs provider 需要对应 Apple 测试凭证；示例服务默认关闭 APNs。运行例子不等于已经验证 Apple 推送送达。

原生与缓存集成测试：

```sh
flutter test integration_test/inbox_test.dart -d <iOS-simulator-id> \
  --dart-define-from-file=../../.local-demo/flutter.json
```

示例任务详情只展示消息内容；真实业务导航应先请求业务 API 验证资源权限。完整接入见[Flutter 文档](../../docs/sdk/flutter.md)。

演示服务重启后请重新执行 flutter run，读取新生成的地址/凭证；使用随机端口隔离不同演示数据库的客户端缓存。正式服务地址保持稳定并保留数据库，不采用此演示策略。

[返回 Polyglot IM 项目首页](../../README.md)
