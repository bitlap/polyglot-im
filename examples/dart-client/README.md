# Polyglot IM Dart Client 示例

先在另一终端从仓库根目录运行 `just demo-server`。

```sh
cd examples/dart-client
dart pub get --enforce-lockfile
dart run live.dart
```

验证 HTTP/WebSocket 通知与单聊表情、幂等、已读，以及关闭 SQLite 后重新打开恢复消息。两个账号通过 scope 共享本地存储，临时文件在关闭所有客户端与连接后清理。默认从根目录 .local-demo/config.json 读取配置，也可传入配置文件路径。

`just test-integration` 还会执行 [postgres_storage.dart](postgres_storage.dart)：使用 demo-server 私有临时配置中的 PostgreSQL URL，验证客户端 HTTP 同步、重启恢复、16 个独立池并发、SQL 失败回滚、隔离、清理、锁超时和池所有权；结束后删除测试 schema 并关闭池。此 URL 不进入 Flutter 用户配置。

[pluggable.dart](pluggable.dart) 展示注入 HTTP、凭证、Socket、原子存储与编解码器；借用的传输由宿主关闭。

[子项目功能与集成](../../dart-client/README.md) · [全部示例](../README.md) · [返回项目首页](../../README.md)
