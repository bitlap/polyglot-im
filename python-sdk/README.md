# Polyglot IM Python SDK

Python 3.11+ 异步通知 SDK，导入 polyglot_im。提供 NotificationSender、ImClient、SQLite 消息缓存、PostgreSQL 业务事务 PostgresOutbox、异步 HTTP/凭证端口、HTTPX 适配器和 WebSocket 接收。网络依赖通过 `[http]` 安装，业务数据库驱动通过 `[postgres]` 安装。

PostgreSQL 连接串由宿主显式传入 `open_postgres_outbox`，可配置地址、数据库、用户、密码、TLS、连接池和租约；也可借用宿主异步连接池。SQLite 仅用于设备本地消息缓存。

[使用文档](../docs/sdk/python.md)，[实际 HTTP/WS 示例](examples/live.py)。宿主管理事件循环、身份与资源生命周期；服务凭证不进入 App。

源码采用 Apache-2.0 许可证。

[返回 Polyglot IM 项目首页](../README.md)
