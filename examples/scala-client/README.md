# Polyglot IM Scala Client 示例

先在另一终端从仓库根目录运行 `just demo-server`。

```sh
cd examples/scala-client
sbt run
```

发送通知、验证幂等并通过用户凭证读取历史。默认读取根目录 .local-demo/config.json，也可用 sbt 'run /absolute/path/config.json' 指定配置。

[ClientWiring.scala](src/main/scala/polyglot/im/examples/ClientWiring.scala) 展示通过端口注入 HTTP、Socket、存储和同一编解码器；由宿主管理传入资源。

[子项目功能与集成](../../scala-client/README.md) · [全部示例](../README.md) · [返回项目首页](../../README.md)
