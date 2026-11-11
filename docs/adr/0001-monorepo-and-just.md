# ADR 0001：Monorepo 与 just

- 状态：接受。
- 背景：协议、Scala 服务与 Client、Dart/Flutter、Python 需要协同演进。用户明确选择 just。
- 决策：单仓库保存协议和各语言原生项目；根 justfile 统一 setup、fmt、lint、test、check、build、package、publish-*。
- 原因：减少跨仓协议漂移，保持生态原生的依赖解析、IDE 和发布兼容；比引入完整跨语言构建图更容易初始化。
- 代价：just 不推断受影响目标，不提供远程缓存或跨语言密闭工具链；CI 明确组织任务，依赖仍分语言管理。
- 约束：发布是显式独立操作；初始化时默认禁用。任何将来替换 Bazel/Pants 的提案须基于实际构建瓶颈重新决策。
