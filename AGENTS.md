# polyglot-im 开发约定

- 面向 Scala、Dart/Flutter、Python 的跨平台 IM 单仓库；统一入口为根目录 justfile。
- Scala 子项目直接位于根目录 scala-sdk / scala-server，各自维护 build.sbt、project 与 Scalafmt 配置，不在根目录聚合 sbt，也不增加 jvm 容器层。
- README 不设置许可证栏目；许可正文只在根目录维护 LICENSE/NOTICE，分发产物从根目录注入许可文件。子项目 README 聚焦功能与使用示例，并链接回根 README。
- 所有可运行示例集中在根目录 examples/<子项目名>/，子项目内部不保留 example/examples 目录。
- 用户文档、设计说明和变更记录使用中文，代码标识符使用英文。
- 修改协议须同时更新 JSON Schema、OpenAPI、共享样例、三端契约测试及兼容性说明。
- 64 位序号及 ID 使用字符串；时间采用 UTC RFC 3339；消息类型允许向前兼容。
- 不把规划能力标记为已实现；README、功能矩阵和集成文档必须保持一致。
- 不提交令牌、设备推送 token、私钥、真实用户数据或外部服务凭证。
- 网络发送、数据库、推送和发布接入应显式配置；不得用内存实现冒充可靠持久化。
- just check 为提交前检查入口；只改中文文档可运行 just check-docs。
- 不访问其他项目的生产数据库或复用其机密配置。

- 全部子项目版本由根目录 VERSION 统一管理；通过 just set-version <X.Y.Z> 更新并刷新锁文件。
- 所有变更记录位于根目录 changelog/<版本号>.md，每个版本一个文件，文件内汇总所有模块变化；不按子项目分目录，也不在子模块维护 CHANGELOG.md。

- Scala 使用 Cats/Cats Effect 的效果多态端口与 Resource；Python 使用 async/await、Protocol 和显式异步资源工厂。核心不创建全局运行时或连接池，适配器不绕过认证、事务或取消语义。

- Mermaid 图统一在 docs/diagrams/*.mmd 维护，通过 just render-diagrams 生成 SVG；每张图只在一个文档位置展示，其他文档使用普通链接引用。系统在上、用户在下，各自分框；系统间用实线，用户与系统用橙色虚线，规划系统用紫色虚框并标注扩展。往返通道分开，连线首尾均垂直于矩形的平直边并避开圆角；通信文字放在图下表格，不放在线上。APNs 提醒保持单向，代码依赖保持单向。修改后逐张检查 SVG/PNG，并用 just check-docs 校验源码摘要、端点、通道间距和图片唯一性。

- 子项目按 api / service / domain / ports / config / adapters（Scala 为 infrastructure）分层，服务端 bootstrap 为组合根；common 仅放纯类型、错误和密码学等小工具，不放业务服务或驱动。核心层不能依赖具体 HTTP、Socket、JDBC、SQLite 或平台通道；通过 just check-layers 检查依赖方向。兼容导出只用于外部旧代码，内部按分层包导入。替换适配器必须保留身份隔离、事务原子性、落盘后 ack 和资源所有权约定。
