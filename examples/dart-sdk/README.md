# Polyglot IM Dart SDK 示例

先在另一终端从仓库根目录运行 `just demo-server`。

```sh
cd examples/dart-sdk
dart pub get --enforce-lockfile
dart run live.dart
```

验证 HTTP/WebSocket 收发、幂等和已读。默认从根目录 .local-demo/config.json 读取配置，也可传入配置文件路径。

[子项目功能与集成](../../dart-sdk/README.md) · [全部示例](../README.md) · [返回项目首页](../../README.md)
