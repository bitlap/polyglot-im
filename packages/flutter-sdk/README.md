# polyglot_im_flutter_sdk

polyglot-im Flutter 生命周期适配，要求 Flutter 3.44+。导出 ImMessage，并提供 ImLifecycleObserver；当前不包含系统推送或聊天 UI。

完整用法见 [Flutter / Dart 集成](../../docs/sdk/flutter.md)。当前通过本地 path 依赖 polyglot_im_dart_sdk，正式发布前需要替换为已发布版本依赖。

Flutter App 只需声明本包依赖，并导入 polyglot_im_flutter_sdk.dart；本包统一导出 Dart 核心公共模型，不需要另行声明或导入 Dart SDK。
