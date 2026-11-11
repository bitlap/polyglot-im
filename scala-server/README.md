# Polyglot IM Scala Server

基于 Cats Effect、http4s、HikariCP 与 PostgreSQL 的持久化通知服务，提供 JWT 鉴权、幂等发送、历史分页、未读/已读、增量同步、WebSocket、设备注册及 iOS APNs 推送。

消息、用户事件流与推送 Outbox 在同一数据库事务提交，客户端可从持久事件流恢复离线消息。

## 启动与发送示例

需要 JDK 17+。在仓库根目录启动临时数据库演示：

```sh
just setup
just demo-server
```

另一终端运行 `just test-live` 验证 Python / Dart 的真实 HTTP/WS 接入，或运行 `just example-ios` 启动 Flutter 示例。演示使用随机凭证及 loopback 端口，退出后删除临时数据库。

持久化部署先准备 PostgreSQL，并按[环境变量模板](../config/.env.example)设置连接、应用身份和签名密钥，再执行 `just run-server`。连接池上限、连接超时和 APNs 均可显式配置。

业务后端使用服务 Token 发送通知：

```sh
curl "$IM_BASE_URL/api/v1/im/notifications" \
  -H "Authorization: Bearer $IM_SERVICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"recipient_id":"user-123","client_message_id":"task:456:completed","type":"task.completed","payload":{"task_id":"456","text":"创作完成"}}'
```

`/health/live` 检查存活，`/health/ready` 检查数据库就绪。用户 Token 用于读取本人消息和注册设备，服务 Token 只保存在后端。

## 测试与性能

在仓库根目录执行：

```sh
sbt 'scalaServer/test'
just test-integration
just benchmark-smoke
```

[配置与部署](../docs/development.md) · [协议与接口](../docs/protocol.md) · [基准报告](../benchmark/README.md) · [返回 Polyglot IM 项目首页](../README.md)
