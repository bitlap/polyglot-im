# Polyglot IM Scala Server

基于 Cats Effect、http4s、HikariCP 与 PostgreSQL 的持久化通知服务。提供 JWT 鉴权、幂等发送、历史分页、未读/已读、增量同步、WebSocket、设备注册与可选 iOS APNs 推送。

消息、用户事件流与推送 Outbox 在同一数据库事务内提交；客户端通过持久事件流恢复连接和离线数据。

## 启动

需要 JDK 17+。在仓库根目录运行临时数据库演示：

```sh
just setup
just demo-server
```

演示使用随机凭证和 loopback 端口，数据库在退出时删除。另一终端运行 `just test-live` 验证 Python / Dart 的真实 HTTP/WS 接入。

持久化部署先创建 PostgreSQL 数据库，按[环境变量模板](../../config/.env.example)配置数据库、应用身份和独立签名密钥，再运行：

```sh
just run-server
```

APNs 需显式配置 Apple 凭证。`/health/live` 检查存活，`/health/ready` 检查数据库就绪；配置详情见[开发与配置](../../docs/development.md)，接口见[协议文档](../../docs/protocol.md)和 [OpenAPI](../../protocol/openapi.yaml)。

## 测试与性能

在仓库根目录运行：

```sh
just test-scala
just test-integration
just benchmark
```

单独测试服务：

```sh
cd jvm
sbt 'scalaServer/test'
```

数据库测试使用独立临时 PostgreSQL。压测覆盖并发写入、历史分页、空增量和 WebSocket 投递，实测结果见[基准报告](../../benchmark/README.md)。

部署、推送重试、容量和备份恢复要求见[架构](../../docs/architecture.md)与[安全运维](../../docs/operations.md)。版本由根目录 [VERSION](../../VERSION) 管理，源码采用 [Apache License 2.0](../../LICENSE)。

[返回 Polyglot IM 项目首页](../../README.md)
