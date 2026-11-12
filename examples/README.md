# Polyglot IM 示例

每个子项目在本目录维护一个独立文件夹；先在仓库根目录执行 `just setup`。

| 子项目 | 示例内容 |
| --- | --- |
| [scala-server](scala-server/README.md) | 临时 PostgreSQL 与 HTTP/WebSocket 演示服务 |
| [scala-client](scala-client/README.md) | Scala 通知发送、幂等、用户历史与 SQLite 重启恢复 |
| [python-client](python-client/README.md) | Python 异步收发、缓存和已读 |
| [dart-client](dart-client/README.md) | Dart HTTP/WebSocket、幂等、已读、SQLite 重启恢复与 PostgreSQL 缓存回归 |
| [flutter-client](flutter-client/README.md) | Flutter iOS 消息中心与本地缓存 |
| [uniapp-client](uniapp-client/README.md) | Node 适配器下的 SDK HTTP/WebSocket 与持久化验收 |
| [benchmark](benchmark/README.md) | 并发和延迟测试命令 |

除 benchmark 自行管理服务外，其他客户端示例连接 `just demo-server` 生成的本机临时环境。`just test-integration` 自动启动服务并运行 Scala、Python、Dart 和 uni-app Client 示例。

[返回项目首页](../README.md)
