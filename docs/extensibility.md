# 异步实现与可插拔架构

## 技术约定

Scala 基线为 Cats 2.12.0、Cats Effect 3.5.7、http4s 0.23.30。Python 核心只依赖标准库，HTTPX 通过 `[http]` 可选依赖接入。websockets 通过可选 [http] 依赖提供，PostgreSQL 业务 Outbox 通过 `[postgres]` 可选依赖使用 psycopg 异步连接池，设备 SQLite 缓存使用标准库线程适配，不把所有后端强加给只用消息模型的用户。

## 依赖方向

| 组件 | 允许的代码依赖 | 装配责任 |
| --- | --- | --- |
| 领域服务 | 端口、纯消息模型 | 不创建外部资源 |
| HTTP / 存储 / 推送适配器 | 端口、纯消息模型 | 实现端口并保留事务与取消语义 |
| 服务端组合根 | 领域服务、端口、适配器 | 配置、实现选择与生命周期 |
| SDK 核心 | 协议模型、传输及缓存端口 | 不持有全局运行时 |
| 宿主 App | SDK、所选适配器 | 认证配置、缓存命名空间与资源释放 |

运行时通信统一见 [README 系统架构](../README.md#系统架构)。

表中的代码依赖保持单向；运行时 HTTP/WSS 双向通信不表示反向代码依赖。

领域服务只依赖端口和纯模型，适配器实现端口，组合根负责选择实现、配置与生命周期。代码引用方向为适配器/服务 → 端口与模型。核心不得读取环境变量、创建全局线程池、静默分配 HTTP 客户端或使用单例连接。

实现 HTTP、认证、SocketConnector、LocalStore 与 PushProvider 端口，以及 PostgreSQL 事务仓储、业务 Outbox、APNs 适配与同步客户端。服务端仓储的可靠写入是一个事务，不能为了插件数量把它拆成互相不一致的多次写入。

## 分层与扩展入口

| 层 | 职责 | 依赖限制 |
| --- | --- | --- |
| api | HTTP DTO、信封、路由、SDK 便捷装配入口 | 把请求交给 service，不执行 SQL |
| service | 同步、重连、鉴权策略、通知范围、推送任务编排 | 依赖 ports、domain、config，不创建具体网络或数据库客户端 |
| domain | 消息、身份、缓存归并与业务校验 | 纯模型与纯函数 |
| ports | 远端 API、HTTP、Socket、编解码、原子存储、鉴权和推送契约 | 不依赖实现，不包含驱动 |
| config | HOCON、连接设置、重试参数、协议常量 | 解析与校验，不打开连接 |
| common | 纯类型、错误和密码学工具 | 不承载业务服务、可变全局状态或驱动 |
| infrastructure / adapters | JDK、http、HTTPX、WebSocket、JDBC、SQLite、APNs、MethodChannel | 实现端口，管理或借用外部资源 |
| bootstrap | 服务端资源装配、迁移、后台任务与释放 | 唯一选择生产默认后端的位置 |
| presentation | Flutter 列表、单聊页面 | 调用同步服务，不直接读数据库或操作平台通道 |

Scala 使用 infrastructure 命名具体实现，Dart/Flutter/Python 使用 adapters。没有职责时不创建空目录；不为凑层次新增通用 util 大文件。`just check-layers` 已接入 `just check`，检查五个子项目的依赖方向，并拦截核心层直接引用具体驱动。

| 子项目 | 应用入口 | 可替换契约 | 默认实现 |
| --- | --- | --- | --- |
| Scala Server | bootstrap.ServerApplication / ServerComponents | ClientRepository、PushRepository、ClusterRepository、Authenticator、ChangeNotifications、PushProvider | infrastructure 下 PostgreSQL、JWT、APNs |
| Scala SDK | api.ApiClient / api.ImClient | RemoteApi、HttpTransport、SocketConnector、LocalStore、WireCodec、NotificationPublisher | JDK HTTP/WS、AtomicFileStore、JSON、JDBC Outbox |
| Dart SDK | HttpApi / ImClient；独立核心为 SyncClient | RemoteApi、HttpTransport、SocketConnector、LocalStore、WireCodec、NotificationPublisher | package:http、web_socket_channel、JSON；缓存由宿主选择 |
| Flutter SDK | IosPushBinding、ImInbox、ImConversations、ImDirectChat | Dart 核心端口、IosPushPlatform | SQLite、MethodChannelPush |
| Python SDK | api.http.ApiClient / api.client.ImClient；独立核心为 service.client.SyncClient | RemoteApi、HttpTransport、SocketConnector、LocalStore、WireCodec、NotificationPublisher | HTTPX、websockets、SQLite、JSON、PostgreSQL Outbox |

### 替换传输与编解码

HTTP 与 Socket 分开注入。Dart 可传 HttpTransport，也保留原有 http.Client 和 socketFactory 参数；两种同类注入方式不能同时指定。Python 默认 websocket 适配器在实际连接时才导入可选依赖，注入自己的 SocketConnector 不会启动 websockets。Scala 使用 Resource 管理 HttpTransport 与 SocketConnector，服务层不引用 JDK HttpClient。

同一连接链路使用同一个 WireCodec：HTTP API 解码响应，底层 HTTP 适配器编码请求，实时同步使用同一帧编码。Scala 向 JdkTransport.resource、ApiClient 和 ImClient.create 传同一实现；Python 向 open_httpx_transport、ApiClient 和 ImClient 传同一实现；Dart 向 HttpApi 和 ImClient 传同一实现。默认 JSON 的 HTTP 信封和 WS 帧保持不变。

RemoteApi 保留已有 v1 的操作与路径契约。替换 HTTP 客户端库可直接实现 HttpTransport；切换到 gRPC、QUIC 或新的消息协议，需要新增 RemoteApi/SocketConnector 适配器以及操作与帧映射，不会仅凭换一个驱动就自动获得这些协议。Scala 的 WireCodec/SocketSession 以文本帧为契约，原生二进制协议需要在适配器边界完成映射或显式演进端口。

可运行工程内的装配示例见 [Scala](../examples/scala-sdk/src/main/scala/polyglot/im/examples/ClientWiring.scala)、[Dart](../examples/dart-sdk/pluggable.dart)、[Python](../examples/python-sdk/pluggable.py)。这些示例接收宿主创建的资源，不从其他项目读取连接或凭证。

### 替换存储与推送

服务端以 ImRepository 汇总资源生命周期及细分事务端口：消息、会话、设备、推送租约和集群配额各有契约。Main.resource(config, components) 接受显式 ServerComponents；默认 ServerComponents.postgres(config) 提供 PostgreSQL、JWT、数据库唤醒和可选 APNs。宿主可替换任意组件的 Resource，HOCON 内置的生产默认仍为 PostgreSQL；其他后端的特有配置由新增装配代码负责解析。

ClientRepository 的发送方法必须原子写入消息、双方事件、会话水位和 Outbox；幂等、成员权限和已读单调性也在同一事务内执行。不能把接口拆分等同于允许多个数据源分别提交。集群配额、票据单次消费、推送领取令牌和租约隔离也是替代实现的必需语义。默认 PostgreSQL SQL、迁移和锁顺序保持不变。

ChangeNotifications 仅提供唤醒提示，事件仍从持久仓储读取。替代实现必须在订阅时立即触发一次，并在通知后端断连时仍至少每五秒触发补查；允许重复提示，不能依赖提示本身保存消息。服务端装配示例见 [ServerWiring](../examples/scala-server/src/main/scala/polyglot/im/server/ServerWiring.scala)。

客户端 LocalStore.commit 必须在一个原子操作里合并消息和游标，成功后才允许 ack。失败不能先公布新游标。跨账号/环境隔离、旧页不回退、重放去重必须保留。Flutter SQLite 由宿主显式打开和关闭；注入的 HTTP 传输为借用资源，客户端关闭后由宿主关闭传输。默认创建的资源由默认装配入口释放。

Flutter 的 PushBindingService 只调用 IosPushPlatform；MethodChannelPush 负责原生事件、权限和 badge。可通过 IosPushBinding(platform: ...) 替换通道。服务端 PushWorker 只依赖 PushRepository/PushProvider；APNs 接受仍不等于用户送达或已读。

### 兼容性

根 SDK 导出、Dart src/client.dart、Python client/transport/message/outbox 旧导入，以及 Scala 原包名均保留兼容导出；业务示例与既有测试继续使用这些入口。Scala 包移动提供源码重编译兼容，不承诺已编译二进制的 ABI 不变。内部代码使用分层包，避免通过兼容门面反向依赖默认适配器。

## Scala：效果多态与资源

- 纯消息模型和 Circe 编解码继续保持纯函数。
- 网络/存储/业务端口使用 `trait Xxx[F[_]]`，返回 `F[A]`；只使用必要的 Cats 约束，例如认证装饰器只需 MonadThrow，HTTP 服务需要 Async。
- IO 在组合根落地，入口为 IOApp.Simple。核心没有 unsafeRunSync、全局 ExecutionContext 或不可取消的 Future 链。
- HTTP 服务、连接池、数据库、Worker 使用 Resource 获取和释放；长任务通过受管理的 fiber、Supervisor 或 FS2 Stream 运行，退出时取消并等待释放。
- `Server.resource[F]` 接收配置、Readiness[F]、额外 HttpRoutes[F] 和 Network[F]。健康路径保留，业务路由通过 Cats `<+>` 组合。
- Main 注入真实 PostgreSQL Readiness，失败返回 503，不泄露外部依赖异常内容。

宿主可通过构造函数、Resource 或依赖注入框架在组合根装配端口。

## Python：原生异步与资源所有权

- 端口用 typing.Protocol 和 async def，业务代码接受注入对象，不通过具体类做 isinstance 分支。
- `HttpTransport.execute`、`TokenProvider.token` 是异步扩展点；认证装饰器组合两者，每次读取凭证，不永久缓存 token。
- HTTPX 适配器可以借用宿主 AsyncClient；借用时不关闭宿主连接池。
- open_httpx_transport 使用 async context manager 拥有连接池，正常、异常和取消退出都会释放；可注入 network 后端以测试或替换底层连接。
- SDK 不调用 asyncio.run，不创建自己的事件循环，不吞掉 CancelledError；同步第三方 SDK 仅在适配层显式使用 asyncio.to_thread，并说明线程操作的取消边界。
- 连接工厂显式指定 timeout 与 trust_env，默认不跟随重定向。URL 与认证策略不能靠“插件灵活”绕过来源限制。

## 插件边界

| 端口 / 扩展点 | 实现方式 | 约束 |
| --- | --- | --- |
| HttpTransport / TokenProvider | Scala 与 Python 已有接口及认证装饰器 | 可替换网络和凭证；只接收相对配置源的路径 |
| HTTP 服务路由 / Readiness | Scala 已实现 | 路由显式组合，健康路径优先；就绪状态不能伪造持久化完成 |
| HttpxTransport | Python 已实现，可选依赖 | 可借用/拥有连接池；不自动重试业务写入 |
| Scala HTTP 客户端适配器 | 已实现 P0 | JdkTransport.resource，管理 HTTP/WS 和执行器 |
| Store / 事务仓储 | 已实现 P0 | 一个端口操作原子提交消息、接收者事件和 Outbox，不能拆成几个独立“可插拔写入” |
| Authenticator / 授权策略 | 已实现 P0 | 应用、用户、服务身份分开；事务内校验必要可见性 |
| LocalStore / 同步事务 | 已实现 P0 | 本地消息与游标原子落盘；更换存储不降低可靠性 |
| PushProvider | APNs 已实现 P1，真机待验收 | APNs 适配；通道接受不等于已读或送达 |
| 持久事件流 / WS | 已实现 P1 | 直接从 PostgreSQL 读取，FS2 背压，Redis 未来可加速 |
| 内容审核 / Webhook / 搜索 | 随对应阶段实施 | 保留权限、限流、审计与失败语义 |

## 扩展方式与禁止的变化

增加后端时实现对应端口，并在组合根显式选择与 Resource/async context manager 中注册；新增协议类型仍需更新 Schema、三端编解码和契约测试。已有端口测试应对替换实现复用。

认证、观测、重试可以做装饰器；重试必须由业务层提供幂等语义与有限策略，通用传输不自动重试 POST。异步并发不改变消息顺序、事务水位或背压要求。配置选择适配器不等于运行时热替换：有状态连接切换需排空、关闭、重新鉴权并恢复游标，没有实现无损热切换。

可插拔是替换实现的能力，不是允许跳过持久化、权限或取消释放的承诺。

## 模块称呼与发布名称

为避免语言、职责与目录混用，模块 ID、源码子目录与变更记录目录统一按“语言/平台 + 职责”命名；发布包带项目名前缀，并按语言生态使用连字符或下划线。

| 模块称呼 | 源码目录 | 发布/导入名称 |
| --- | --- | --- |
| Scala SDK | scala-sdk | Maven artifact: polyglot-im-scala-sdk；Scala package: polyglot.im |
| Scala Server | scala-server | polyglot-im-scala-server |
| Dart SDK | dart-sdk | pub package: polyglot_im_dart_sdk |
| Flutter SDK | flutter-sdk | pub package: polyglot_im_flutter_sdk，依赖 Dart SDK 并适配生命周期 |
| Python SDK | python-sdk | distribution: polyglot-im-python-sdk；import: polyglot_im |

Dart/Flutter SDK 直接位于仓库根目录，不再增加 `packages/` 容器层。模块 ID 为 scala-sdk、scala-server、dart-sdk、flutter-sdk、python-sdk；Scala 运行时包名 polyglot.im 和 Python 导入名 polyglot_im 仍按各生态习惯保留。所有模块版本、changelog、CI 和示例都引用同一组目录。

Flutter App 推荐只依赖 Flutter SDK。它导出 Dart SDK 的公共模型，宿主不需要维护两套客户端实例；纯 Dart 核心负责协议与同步逻辑，Flutter 层负责生命周期、SQLite 和 iOS APNs 适配。只依赖 Dart SDK 也是可选方案，但平台适配由宿主自行承担，不能因此获得Flutter 的原生推送绑定。
