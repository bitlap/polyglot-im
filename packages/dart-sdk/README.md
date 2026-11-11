# polyglot_im_dart_sdk

纯 Dart HTTP/WS 通知 SDK，Dart 3.12+。提供 ImMessage、HttpApi、NotificationSender、ImClient、LocalStore 和可注入网络/存储端口。Flutter App 使用 Flutter SDK 单一入口即可。

[集成文档](../../docs/sdk/flutter.md)，[真实网络示例](example/live.dart)。默认没有隐式持久缓存；生产应注入 LocalStore，显式使用 VolatileStore 时重启从服务端恢复。

源码 Apache-2.0，版本 0.1.0，尚未发布 pub.dev。
