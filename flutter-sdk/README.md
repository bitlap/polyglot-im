# polyglot_im_flutter_sdk

Flutter 3.44+ 的消息 SDK 单一入口，重导出 Dart HTTP/WS 客户端，提供 SQLite 持久缓存、ImInbox 消息页、生命周期和 iOS APNs 原生绑定。推送首批仅 iOS；CocoaPods / SwiftPM 均提供适配。

[集成文档](../docs/sdk/flutter.md)，[iOS 示例](../examples/flutter_app/README.md)。原生编译及模拟器集成已验证，Apple 远程推送真机验收仍需自己的测试配置。

源码 Apache-2.0，版本 0.1.0，尚未发布 pub.dev。本地 path 依赖保留模块内布局；正式注册表发布需先发布 Dart 包并替换 path 依赖。
