# Polyglot IM Scala Server

基于 Cats Effect、http4s、HikariCP 与 PostgreSQL 的持久化通知服务，提供 JWT 鉴权、幂等发送、历史分页、未读/已读、增量同步、WebSocket、设备注册及 iOS APNs 推送。

消息、用户事件流与推送 Outbox 在同一数据库事务提交，客户端可从持久事件流恢复离线消息。支持多实例共享主库、跨实例事件唤醒、全局限流、推送租约隔离和并行 Worker，部署要求见[多实例方案](../docs/distributed.md)。

## 启动与发送示例

需要 JDK 17+。在仓库根目录启动临时数据库演示：

```sh
just setup
just demo-server
```

另一终端运行 `just test-live` 验证 Python / Dart 的真实 HTTP/WS 接入，或运行 `just example-ios` 启动 Flutter 示例。演示使用随机凭证及 loopback 端口，退出后删除临时数据库。

持久化部署先准备 PostgreSQL，复制 [HOCON 模板](../config/server.example.conf)，填写连接、应用身份和三个不同的随机签名密钥。从仓库根目录运行：

```sh
cp config/server.example.conf config/server.local.conf
# 编辑 config/server.local.conf 后启动
just run-server --config "$PWD/config/server.local.conf"
```

采用 PureConfig 类型化读取，默认加载 classpath `application.conf` 与 `reference.conf` 默认值。也可在本目录执行 `sbt -Dconfig.file=/absolute/path/server.conf run`，或通过 `POLYGLOT_IM_CONFIG` 指定文件。JVM 的 `-Dpolyglot-im.*` 属性优先于环境变量覆盖，随后是应用文件和默认值；不自动加载 `.env`。

配置包括 HTTP、PostgreSQL 连接池、鉴权、APNs 和限流。非法配置在建立数据库连接前失败，错误不回显密钥。APNs 私钥相对路径以配置文件所在目录解析。配置结构、覆盖规则及[环境变量兼容模板](../config/.env.example)见[开发与配置](../docs/development.md)。

业务后端使用服务 Token 发送通知：

```sh
curl "$IM_BASE_URL/api/v1/im/notifications" \
  -H "Authorization: Bearer $IM_SERVICE_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"recipient_id":"user-123","client_message_id":"task:456:completed","type":"task.completed","payload":{"task_id":"456","text":"创作完成"}}'
```

`/health/live` 检查存活，`/health/ready` 检查数据库就绪。用户 Token 用于读取本人消息和注册设备，服务 Token 只保存在后端。

## 测试与性能

在本目录执行 `sbt test`，启动服务使用 `sbt run`。构建通过源码依赖引用相邻目录的 Scala SDK，无需先发布本地包。

仓库根目录可统一运行：

```sh
just test-scala
just test-integration
just benchmark-smoke
```

[演示服务源码](../examples/scala-server/README.md) · [配置与部署](../docs/development.md) · [协议与接口](../docs/protocol.md) · [基准报告](../benchmark/README.md) · [返回 Polyglot IM 项目首页](../README.md)
