# Polyglot IM 文档

| 文档 | 内容 |
| --- | --- |
| [技术设计](design.md) | 目标、技术选型、领域模型与状态机 |
| [系统架构](architecture.md) | 多实例路由、用户/系统消息流程、持久化与平台适配 |
| [多实例部署](distributed.md) | 跨实例唤醒、全局限流、租约接管及数据库主备 |
| [消息一致性与可达性](delivery.md) | 分链路投递语义、幂等效果、顺序、确认与故障恢复边界 |
| [模块入口与资源管理](extensibility.md) | Cats/Cats Effect、Python 异步、资源所有权、扩展端口与模块命名 |
| [功能矩阵](features.md) | 当前已实现能力与验收边界 |
| [协议与接口](protocol.md) | 消息与同步事件、错误语义、REST/WS 实现 |
| [Client 集成总览](client/README.md) | Client 能力和应用侧职责 |
| [Scala Client](client/scala.md) | 本地依赖、通知发送、接收、事务 Outbox |
| [Dart Client](client/dart.md) | 纯 Dart SQLite / PostgreSQL、存储注入、事务与资源管理 |
| [客户端存储](client/storage.md) | 全端本地持久化、Python/Scala/Dart PostgreSQL、事务与资源所有权 |
| [Flutter Client](client/flutter.md) | path 依赖、生命周期、SQLite、消息页与 iOS APNs |
| [uni-app Client](client/uniapp.md) | 本地 npm、通知/单聊、实时生命周期、持久化及平台边界 |
| [Python Client](client/python.md) | uv 安装、异步发送、同步、缓存与事务 Outbox |
| [业务接入](integration.md) | 业务 Outbox、现有登录与首次接入 |
| [开发与配置](development.md) | 工具链、目录、格式、环境与账号隔离 |
| [构建与发布](building.md) | just、CI、批量版本设置、产物与发布前置条件 |
| [变更记录](../changelog/README.md) | 根目录按版本汇总的全部模块变更 |
| [安全与运维](operations.md) | 授权、限流、数据治理、监控与故障恢复 |
| [验证记录](verification.md) | 已运行命令、结果与边界 |
| [单聊集成](direct-chat.md) | 文字、Unicode 表情、会话及已读回执 |
| [并发与延迟基准](../benchmark/README.md) | 真实 PostgreSQL、HTTP/WS、测量条件与原始结果 |
| [架构决策记录](adr/0001-monorepo-and-just.md) | Monorepo、原生工具链与 just 的取舍 |

服务端 HTTP/WS 路由与数据结构见 [OpenAPI](../protocol/openapi.yaml)；SDK 方法见各端集成文档。

[返回项目首页](../README.md)
