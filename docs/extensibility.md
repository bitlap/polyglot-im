# 异步实现与可插拔架构

## 约定与参考

Scala 参考现有 SnapFlow 的 `MessageService[F]`、`IOApp.Simple` 与 AppModule 组合方式，采用 Cats、Cats Effect、http4s。Python 参考 TrendForge 的端口/适配器、`asyncio`、`httpx.AsyncClient` 与异步资源工厂。只借鉴设计和依赖用法，没有复制私有项目业务实现、版权头或配置。

Scala 基线为 Cats 2.12.0、Cats Effect 3.5.7、http4s 0.23.30；沿用现有项目的兼容范围，不代表最新版。Python 核心只依赖标准库，HTTPX 通过 `[http]` 可选依赖接入。未来接 websockets/aiosqlite 时也以独立适配器与可选依赖提供，不把所有后端强加给只用消息模型的用户。

## 依赖方向

```mermaid
flowchart LR
  Root[组合根 / 宿主程序] --> Core[领域服务与端口]
  Root --> Adapters[HTTP / 存储 / 推送适配器]
  Adapters --> Core
  Core --> Domain[纯消息模型与协议]
```

领域服务只依赖端口和纯模型，适配器实现端口，组合根负责选择实现、配置与生命周期。代码引用方向为适配器/服务 → 端口与模型。核心不得读取环境变量、创建全局线程池、静默分配 HTTP 客户端或使用单例连接。

当前提供 HTTP 传输端口、认证装饰器、Scala 可注入就绪检查和路由组合、Python HTTPX 适配器。消息存储、事务写入、同步和推送端口在对应 P0/P1 功能实施时才落地，不预先开放一组不可工作的空方法。

## Scala：效果多态与资源

- 纯消息模型和 Circe 编解码继续保持纯函数。
- 网络/存储/业务端口使用 `trait Xxx[F[_]]`，返回 `F[A]`；只使用必要的 Cats 约束，例如认证装饰器只需 MonadThrow，HTTP 服务需要 Async。
- IO 在组合根落地，入口为 IOApp.Simple。核心没有 unsafeRunSync、全局 ExecutionContext 或不可取消的 Future 链。
- HTTP 服务、连接池、数据库、Worker 使用 Resource 获取和释放；长任务通过受管理的 fiber、Supervisor 或 FS2 Stream 运行，退出时取消并等待释放。
- `Server.resource[F]` 接收配置、Readiness[F]、额外 HttpRoutes[F] 和 Network[F]。健康路径保留，业务路由通过 Cats `<+>` 组合。
- 默认 Readiness 返回 false，仍为 HTTP 503；宿主可注入真实检查，失败也保持 503，不泄露外部依赖异常内容。

现有 SnapFlow 的 Distage 可以在宿主组合根绑定这些端口，polyglot-im 核心不强制引入某一个 DI 框架。其他项目也可以直接通过构造函数/Resource 装配。

## Python：原生异步与资源所有权

- 端口用 typing.Protocol 和 async def，业务代码接受注入对象，不通过具体类做 isinstance 分支。
- `HttpTransport.execute`、`TokenProvider.token` 是当前异步扩展点；认证装饰器组合两者，每次读取凭证，不永久缓存 token。
- HTTPX 适配器可以借用宿主 AsyncClient；借用时不关闭宿主连接池。
- open_httpx_transport 使用 async context manager 拥有连接池，正常、异常和取消退出都会释放；可注入 network 后端以测试或替换底层连接。
- SDK 不调用 asyncio.run，不创建自己的事件循环，不吞掉 CancelledError；同步第三方 SDK 仅在适配层显式使用 asyncio.to_thread，并说明线程操作的取消边界。
- 连接工厂显式指定 timeout 与 trust_env，默认不跟随重定向。URL 与认证策略不能靠“插件灵活”绕过来源限制。

## 插件边界及实施阶段

| 端口 / 扩展点 | 当前状态 | 约束 |
| --- | --- | --- |
| HttpTransport / TokenProvider | Scala 与 Python 已有接口及认证装饰器 | 可替换网络和凭证；只接收相对配置源的路径 |
| HTTP 服务路由 / Readiness | Scala 已实现 | 路由显式组合，健康路径优先；就绪状态不能伪造持久化完成 |
| HttpxTransport | Python 已实现，可选依赖 | 可借用/拥有连接池；不自动重试业务写入 |
| Scala HTTP 客户端适配器 | 规划 P0 | HttpTransport[F] 可接 http4s Client[F]，当前未提供默认实现 |
| NotificationJournal / 事务仓储 | 规划 P0 | 一个端口操作原子提交消息、接收者事件和 Outbox，不能拆成几个独立“可插拔写入” |
| IdentityVerifier / 授权策略 | 规划 P0 | 应用、用户、服务身份分开；事务内校验必要可见性 |
| LocalStore / 同步事务 | 规划 P0 | 本地消息与游标原子落盘；更换存储不降低可靠性 |
| PushProvider | 规划 P1 | APNs/FCM/厂商适配；通道接受不等于已读或送达 |
| EventPublisher / 路由 | 规划 P1 | Redis/队列只加速，不能成为唯一持久化副本 |
| 内容审核 / Webhook / 搜索 | 随对应阶段实施 | 保留权限、限流、审计与失败语义 |

## 扩展方式与禁止的变化

增加后端时实现对应端口，并在组合根显式选择与 Resource/async context manager 中注册；新增协议类型仍需更新 Schema、三端编解码和契约测试。已有端口测试应对替换实现复用。

认证、观测、重试可以做装饰器；重试必须由业务层提供幂等语义与有限策略，通用传输不自动重试 POST。异步并发不改变消息顺序、事务水位或背压要求。配置选择适配器不等于运行时热替换：有状态连接切换需排空、关闭、重新鉴权并恢复游标，当前没有实现无损热切换。

可插拔是替换实现的能力，不是允许跳过持久化、权限或取消释放的承诺。

## 模块称呼与发布名称

为避免语言、职责与目录混用，文档统一按“语言/平台 + 职责”称呼；当前包名保持不变。

| 模块称呼 | 源码目录 | 当前发布/导入名称 |
| --- | --- | --- |
| Scala SDK | jvm/sdk | Maven artifact: polyglot-im-scala；Scala package: polyglot.im |
| Scala Server | jvm/server | polyglot-im-server |
| Dart SDK | packages/polyglot_im_client | pub package: polyglot_im_client |
| Flutter SDK | packages/polyglot_im_flutter | pub package: polyglot_im_flutter，依赖 Dart SDK 并适配生命周期 |
| Python SDK | python | distribution: polyglot-im-python；import: polyglot_im |

`packages/` 只是目录，`client` 表示客户端职责，`flutter` 表示平台适配；发布名称的连字符与导入名称的下划线遵循各生态习惯。若将来统一改包名，需要连同模块版本注册表、changelog 路径、锁文件和示例一起迁移，不能只改 README。

## 参考资料

- [Cats Effect 教程](https://typelevel.org/cats-effect/docs/tutorial)：效果与 Resource 的取消/释放语义。
- [http4s 服务组合](https://http4s.org/v0.23/service/)：HttpRoutes 与组合。
- [HTTPX 异步支持](https://www.python-httpx.org/async/)：AsyncClient 与连接关闭。
