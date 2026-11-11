# SDK 集成总览

当前版本 0.1.0，源码可集成，尚未向 Maven、PyPI 或 pub.dev 发布。

| 端 | 生产者 | 用户客户端 | 默认持久化 | 实时 |
| --- | --- | --- | --- | --- |
| Scala | NotificationSender[F] | ImClient[F] | AtomicFileStore[F] | FS2 Stream + JDK WebSocket |
| Python | NotificationSender | ImClient | SQLite | 异步迭代器 + websockets |
| Dart | NotificationSender | ImClient | 注入 LocalStore；VolatileStore 仅用于显式降级 | Stream + WebSocketChannel |
| Flutter | 不应持有服务凭证 | 重导出 Dart ImClient + ImInbox | SqliteStore | 前台 WS、HTTP 补拉、iOS APNs |

- [Scala 使用](scala.md)
- [Python 使用](python.md)
- [Flutter / Dart 使用](flutter.md)

宿主提供登录凭证、环境/应用/用户命名空间和稳定设备 ID。用户令牌只能读取自己的数据；服务令牌只能向授权接收者发送授权类型。服务凭证禁止打包到 App。消息存储、传输、认证、WebSocket 和 APNs Provider 均有可替换端口。

先初始化缓存和会话，然后同步，再订阅。HTTP 是正确性基础，WS 和 APNs 用于及时发现变化。消息与游标同事务落盘后才确认。SDK 自动重试连接错误、429 和部分 5xx，次数有限；每次发送必须使用来自业务事件的稳定幂等键，不能在重试时重新生成。

退出前调用客户端 close，解绑设备；宿主清除凭证。环境或账号改变时关闭旧实例，创建新 scope，禁止复用旧账户客户端。缓存不持久化 Bearer。403、幂等冲突和身份不匹配应由宿主处理，不能无限重试。
