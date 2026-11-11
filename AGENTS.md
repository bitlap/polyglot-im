# polyglot-im 开发约定

- 面向 Scala、Dart/Flutter、Python 的跨平台 IM 单仓库；统一入口为根目录 justfile。
- 用户文档、设计说明和变更记录使用中文，代码标识符使用英文。
- 修改协议须同时更新 JSON Schema、OpenAPI、共享样例、三端契约测试及兼容性说明。
- 64 位序号及 ID 使用字符串；时间采用 UTC RFC 3339；消息类型允许向前兼容。
- 不把规划能力标记为已实现；README、功能矩阵和集成文档必须保持一致。
- 不提交令牌、设备推送 token、私钥、真实用户数据或外部服务凭证。
- 网络发送、数据库、推送和发布接入应显式配置；不得用内存实现冒充可靠持久化。
- just check 为提交前检查入口；只改中文文档可运行 just check-docs。
- 不访问其他项目的生产数据库或复用其机密配置。

- 全部子项目版本由根目录 VERSION 统一管理；通过 just set-version <X.Y.Z> 更新并刷新锁文件。
- 所有变更记录位于根目录 changelog/<模块名>/<版本号>.md，不在子模块维护 CHANGELOG.md。
