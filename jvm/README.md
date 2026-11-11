# Polyglot IM JVM

Scala 3 / sbt 构建入口，统一组织以下模块：

- [Scala SDK](scala-sdk/README.md)：通知发送、同步客户端、缓存端口与业务事务 Outbox。
- [Scala Server](scala-server/README.md)：PostgreSQL 通知服务、HTTP/WebSocket 与 APNs。
- [Benchmark](../benchmark/README.md)：独立压测服务夹具与并发、延迟测量。

需要 JDK 17+。在仓库根目录使用 `just test-scala`、`just build-scala`；也可在本目录运行 `sbt test`、`sbt compile package`。构建配置从根目录 [VERSION](../VERSION) 读取版本。

[返回 Polyglot IM 项目首页](../README.md)
