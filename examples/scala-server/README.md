# Polyglot IM Scala Server 示例

```sh
cd examples/scala-server
sbt run
```

启动临时 PostgreSQL、HTTP/WS 服务和随机演示凭证，配置写入根目录 .local-demo。仅绑定 loopback，退出后删除演示数据库。

[ServerWiring.scala](src/main/scala/polyglot/im/server/ServerWiring.scala) 展示通过 ServerComponents 替换鉴权 Resource；仓储、唤醒与推送也可在同一组合根替换。

[子项目功能与集成](../../scala-server/README.md) · [全部示例](../README.md) · [返回项目首页](../../README.md)
