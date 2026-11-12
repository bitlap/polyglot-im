# uni-app Client

本地 npm 包 `@polyglot-im/uniapp-client`，提供通知、文字与 Unicode 单聊、会话分页、已读回执、可靠 HTTP/WS 同步、设备 token/推送偏好和可信服务端通知发送接口。默认 uni.request、SocketTask 和 uni 持久化；凭证、传输、存储、codec、重试均可替换，不依赖 Node 运行时。

完整安装、接口、生命周期、可靠性与平台边界见 [uni-app 集成文档](../docs/client/uniapp.md)，当前各端对照见 [Client 总览](../docs/client/README.md)。SDK 不含页面，不负责小程序微信订阅消息或原生 iOS 权限/APNs token 获取。服务凭证只能用于可信服务端。

运行 `just test-uniapp`、`just build-uniapp`。`just test-integration` 从 npm tgz 安装到独立工程并连接临时真实 HTTP/WS/PostgreSQL。示例集中在 [examples/uniapp-client](../examples/uniapp-client/README.md)，本地包包含根 LICENSE/NOTICE，不上传 registry。

[返回项目首页](../README.md)
