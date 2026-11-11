# Polyglot IM 文档

| 文档 | 内容 |
| --- | --- |
| [技术设计](design.md) | 目标、领域模型、可靠性与演进决策 |
| [系统架构](architecture.md) | 模块、数据流、持久化、多节点与平台适配 |
| [异步与可插拔设计](extensibility.md) | Cats/Cats Effect、Python 异步、资源所有权、扩展端口与模块命名 |
| [功能矩阵](features.md) | 所有规划能力、实施阶段、验收标准 |
| [协议与接口](protocol.md) | 消息与同步事件、错误语义、REST/WS 实现 |
| [SDK 集成总览](sdk/README.md) | SDK 能力和应用侧职责 |
| [Scala SDK](sdk/scala.md) | 本地依赖、通知发送、接收、事务 Outbox |
| [Flutter / Dart SDK](sdk/flutter.md) | path 依赖、生命周期、SQLite、消息页与 iOS APNs |
| [Python SDK](sdk/python.md) | uv 安装、异步发送、同步、缓存与事务 Outbox |
| [业务接入](integration.md) | 业务 Outbox、现有登录与旧消息迁移边界 |
| [开发与配置](development.md) | 工具链、目录、格式、环境与账号隔离 |
| [构建与发布](building.md) | just、CI、批量版本设置、产物与发布前置条件 |
| [变更记录](../changelog/README.md) | 根目录按版本汇总的全部模块变更 |
| [安全与运维](operations.md) | 授权、限流、数据治理、监控与故障恢复 |
| [实施与验收](roadmap.md) | App 优先的 P0–P3 排序、执行步骤、阶段门槛与测试范围 |
| [验证记录](verification.md) | 已运行命令、结果与边界 |
| [架构决策记录](adr/0001-monorepo-and-just.md) | Monorepo、原生工具链与 just 的取舍 |

可执行接口的唯一清单为 [OpenAPI](../protocol/openapi.yaml)。

[返回项目首页](../README.md)

- [并发与延迟基准](../benchmark/README.md)：真实 PostgreSQL、HTTP/WS 与原始延迟样本。
