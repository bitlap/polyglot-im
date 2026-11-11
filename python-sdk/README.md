# polyglot-im-python-sdk

Python 3.11+ 异步通知 SDK，导入 polyglot_im。提供 NotificationSender、ImClient、SQLite 消息缓存、业务事务 SqliteOutbox、异步 HTTP/凭证端口、HTTPX 适配器和 WebSocket 接收。网络依赖通过 [http] 可选项安装。

[使用文档](../docs/sdk/python.md)，[实际 HTTP/WS 示例](examples/live.py)。宿主管理事件循环、身份与资源生命周期；服务凭证不进入 App。

源码 Apache-2.0，统一版本 0.1.0，尚未发布 PyPI。
