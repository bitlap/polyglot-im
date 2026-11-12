# polyglot-im 开发约定

- 面向 Scala、Dart/Flutter、Python、uni-app 的跨平台 IM 单仓库；统一入口为根目录 justfile。
- Scala 子项目直接位于根目录 scala-client / scala-server，各自维护 build.sbt、project 与 Scalafmt 配置，不在根目录聚合 sbt，也不增加 jvm 容器层。
- README 不设置许可证栏目；许可正文只在根目录维护 LICENSE/NOTICE，分发产物从根目录注入许可文件。子项目 README 聚焦功能与使用示例，并链接回根 README。
- 所有可运行示例集中在根目录 examples/<子项目名>/，子项目内部不保留 example/examples 目录。
- 用户文档、设计说明和变更记录使用中文，代码标识符使用英文。
- 手写源码的类与公共方法使用对应语言的中文文档注释，说明实际职责、关键参数语义、返回结果与事务或资源约束；不以复述方法名代替说明。
- 修改协议须同时更新 JSON Schema、OpenAPI、共享样例、Scala/Dart/Python/JavaScript 四种语言契约测试及兼容性说明。
- 64 位序号及 ID 使用字符串；时间采用 UTC RFC 3339；消息类型允许向前兼容。
- 后续规划统一维护在 GitHub issue；仓库文档只描述当前能力、使用方式和验收边界，不链接 issue 路线图或规划。
- 不提交令牌、设备推送 token、私钥、真实用户数据或外部服务凭证。
- 网络发送、数据库、推送和发布接入应显式配置；不得用内存实现冒充可靠持久化。
- just check 为提交前检查入口；只改中文文档可运行 just check-docs。
- 不访问其他项目的生产数据库或复用其机密配置。
- PostgreSQL 当前表显式使用 snap_flow.im_xxx；尚未上线，只初始化当前结构，不保留旧 IM 数据升级链。

- 全部子项目版本由根目录 VERSION 统一管理；通过 just set-version <X.Y.Z> 更新并刷新锁文件。
- 所有变更记录位于根目录 changelog/<版本号>.md，每个版本一个文件，文件内汇总所有模块变化；不按子项目分目录，也不在子模块维护 CHANGELOG.md。

- Scala 使用 Cats/Cats Effect 的效果多态端口与 Resource；Python 使用 async/await、Protocol 和显式异步资源工厂。核心不创建全局运行时或连接池，适配器不绕过认证、事务或取消语义。

- 图示仅维护 docs/diagrams/*.svg，不保留 .mmd 源文件或生成命令。每张图只在一个文档位置展示，其他文档使用普通链接。系统在上、用户在下，各自分框；系统间实线、用户与系统橙色虚线；往返通道分开，首尾垂直于矩形平直边，通信文字放在图下表格。APNs 提醒保持单向。修改后检查 SVG，并用 just check-docs 校验端点、通道间距与图片唯一性。

- 子项目按 api / service / domain / ports / config / adapters（Scala 为 infrastructure）分层，服务端 bootstrap 为组合根；common 仅放纯类型、错误和密码学等小工具，不放业务服务或驱动。核心层不能依赖具体 HTTP、Socket、JDBC、SQLite 或平台通道；通过 just check-layers 检查依赖方向。Server 不保留旧包名转发；内部按分层包导入。替换适配器必须保留身份隔离、事务原子性、落盘后 ack 和资源所有权约定。
