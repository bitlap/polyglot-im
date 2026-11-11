# Polyglot IM Scala SDK

基于 Scala 3、Cats / Cats Effect 的通知 SDK。提供通知发送、HTTP/WebSocket 同步客户端、账户隔离缓存与 JDBC 业务事务 Outbox；网络、凭证、本地存储均通过端口注入，以 `F[_]`、FS2 与 `Resource` 管理异步操作和资源生命周期。

## 集成

包命名空间为 `polyglot.im`，构建模块为 `scalaSdk`，产物名为 `polyglot-im-scala-sdk`。JDK 17+、Scala 3.6.3。

[Scala 集成文档](../../docs/sdk/scala.md)包含通知生产者、用户客户端、缓存与凭证配置；[业务接入文档](../../docs/integration.md)说明业务事务与 Outbox 的原子提交边界。用户端使用用户凭证，服务凭证仅用于可信后端。

## 测试与构建

在仓库根目录运行：

```sh
just setup
just test-scala
just build-scala
```

单独测试 SDK：

```sh
cd jvm
sbt 'scalaSdk/test'
```

测试涵盖协议编解码、认证刷新、账户隔离、缓存事务、同步和资源释放。产物位于 `jvm/scala-sdk/target/scala-3.6.3/`。

版本由根目录 [VERSION](../../VERSION) 管理，变更见[模块记录](../../changelog/scala-sdk/0.1.0.md)。源码采用 [Apache License 2.0](LICENSE)（[版权声明](NOTICE)）。

[返回 Polyglot IM 项目首页](../../README.md)
