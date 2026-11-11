# polyglot-im 文档

| 文档 | 内容 |
| --- | --- |
| [技术设计](design.md) | 目标、领域模型、可靠性与演进决策 |
| [系统架构](architecture.md) | 模块、数据流、持久化、多节点与平台适配 |
| [功能矩阵](features.md) | 所有规划能力、实施阶段、验收标准 |
| [协议与接口](protocol.md) | 消息与同步事件、错误语义、规划 REST/WS 接口 |
| [SDK 集成总览](sdk/README.md) | 当前 SDK 能力和应用侧职责 |
| [Scala SDK](sdk/scala.md) | 本地依赖、消息编解码、目标服务端接入 |
| [Flutter / Dart SDK](sdk/flutter.md) | path 依赖、生命周期、多端存储与推送规划 |
| [Python SDK](sdk/python.md) | uv 安装、编解码、任务与机器人规划 |
| [开发与配置](development.md) | 工具链、目录、格式、环境与账号隔离 |
| [构建与发布](building.md) | just、CI、版本、产物与发布前置条件 |
| [安全与运维](operations.md) | 授权、限流、数据治理、监控与故障恢复 |
| [实施与验收](roadmap.md) | 阶段依赖、交付标准、测试范围 |
| [验证记录](verification.md) | 已运行命令、结果与当前边界 |
| [架构决策记录](adr/0001-monorepo-and-just.md) | Monorepo、原生工具链与 just 的取舍 |

除明确标注“已实现”的内容外，消息服务行为和 SDK 网络 API 均为设计约定。可执行接口的唯一清单为 [OpenAPI](../protocol/openapi.yaml)。

[返回项目首页](../README.md)
