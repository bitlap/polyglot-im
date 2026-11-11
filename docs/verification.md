# 验证记录与边界

## 2026-11-11 示例集中与根目录许可

全部子项目删除 LICENSE/NOTICE 副本，许可正文仅根目录维护；Scala JAR、Dart/Flutter 归档和 Python sdist/wheel 从根目录注入完整内容，Python wheel 保留 License-Expression 与 License-File 元数据。逐项比对归档许可文件及统一版本记录与根目录一致。

所有可运行示例迁至 examples/<子项目名>/：Scala 演示服务与 SDK 分别独立构建，Dart 示例使用独立 pubspec，Python 示例复用 SDK 环境，Flutter iOS 示例移动至 examples/flutter-sdk；另外提供 benchmark 使用入口。just、CI、版本脚本及集成文档同步更新。

`just check package test-integration benchmark-smoke` 通过，96 项测试通过；真实服务中 Python/Dart HTTP/WS 收发及 Scala SDK 发送、幂等和历史读取通过。`just build-ios` 在迁移后的示例路径成功生成无签名模拟器 Runner.app；不计为 Apple 真机推送验收。

## 2026-11-11 Scala 独立 sbt 构建

scala-sdk、scala-server 和 benchmark 分别在自己的目录维护 build.sbt、project/build.properties、project/plugins.sbt 与 Scalafmt 配置，根目录不再包含 sbt 配置。服务端通过 RootProject 引用 SDK 源码，benchmark 引用服务端源码；各自读取根 VERSION，just 只负责切换目录编排。数据库测试夹具、演示服务与压测脚本使用所属子项目的 sbt 命令。

`just check package test-integration benchmark-smoke` 通过，共 96 项测试；全部分发产物构建成功，Python/Dart 实际 HTTP/WS 收发与压测冒烟通过。另核验三个 Scala 模块的独立配置及 JAR 许可资源，根 README 许可证栏目已移除。

## 2026-11-11 Scala 子项目平铺与 README 精简

Scala SDK、服务端平铺为根目录 scala-sdk / scala-server；build.sbt、project 与 Scalafmt 配置移至根目录。just、版本脚本、数据库测试夹具、跨语言集成和 benchmark 均从根目录启动 sbt。演示服务默认配置目录通过测试 JVM 的根路径定位，不再依赖旧目录层级。

`just check package test-integration benchmark-smoke` 全部通过：93 项测试，Scala JAR、Python wheel/sdist、Dart/Flutter 归档构建成功，Python / Dart 实际 HTTP/WS 收发通过，benchmark 冒烟零错误。另核验三个 Scala 模块 JAR 携带一致的 LICENSE/NOTICE；许可说明只保留在根 README，子项目 README 提供功能、使用示例和根文档链接。

## 2026-11-11 PostgreSQL 业务存储与配置

Python 业务 Outbox 替换为异步 PostgreSQL，设备本地 SQLite 缓存保留。真实 PostgreSQL 测试覆盖业务回滚/提交、重复事件冲突、并发 enqueue/领取、不可重试与临时错误、取消后租约恢复、过期确认隔离和连接池所有权。测试使用独立随机 schema；本地由临时 PostgreSQL 夹具启动，CI 使用专用 PostgreSQL 16 服务。测试不读取服务端或业务生产配置。

服务端仅接受 PostgreSQL JDBC URL；连接池上限与等待超时可配置，配置测试验证默认值、覆盖及非法值启动前拒绝。Python wheel/sdist 构建通过，干净环境验证核心 wheel 不安装 PostgreSQL 驱动仍可导入，驱动保持可选。全仓检查通过共 89 项（Python 40、Scala SDK 13、Scala Server 14、Dart 11、Flutter SDK 6、Flutter 示例 1、benchmark 4）。

复现：`just check`。Python 单独执行 `just test-python`；可用 POLYGLOT_IM_TEST_POSTGRES_DSN 显式指定专用测试数据库，缺省启动临时嵌入式 PostgreSQL，需要 JDK/sbt。Python SDK 不自动读取此测试环境变量，生产连接由宿主显式传入。

## 2026-11-11 单元测试与并发基准

新增五个 SDK/服务模块的认证、同步、隔离、缓存失败、通知校验与 iOS 推送桥接测试，benchmark 增加分位数、失败率和多轮聚合测试。全仓共 82 项：Python 35、Scala SDK 13、Scala Server 12、Dart 11、Flutter SDK 6、Flutter 示例 1、benchmark 4。

`just check` 为统一验收入口；`just benchmark-smoke` 已在真实临时 PostgreSQL / HTTP / WebSocket 上通过，全部请求成功。正式并发级别 1/8/32/128 各 3 轮，完成 60 组、64,224 次测量且全部成功；完整实测、环境和逐条延迟样本见[基准报告](../benchmark/README.md)。持久化同步提交开启；包含推送 Outbox 写入，不包含 Apple APNs 网络投递。

## 2026-11-11 P0/P1 通知服务与全部 SDK

代码版本 0.1.0，尚未上传公共包仓库。全仓检查共 56 项通过（Python 29、Scala SDK 9、Scala Server 7、Dart 7、Flutter SDK 3、Flutter 示例 1），另有 iOS 模拟器集成 2 项通过。下列测试使用本地测试数据库与随机演示凭证，不读取生产数据。

- 真实 PostgreSQL：并发发送、幂等冲突、跨账户隔离、稳定分页、已读单调、一次性票据、设备退出、Outbox 领取及游标过期/快照恢复。
- 真实 http4s HTTP/WebSocket + Scala SDK：发送、幂等重放、同步、多设备已读和用户隔离。
- Python / Dart 连接同一实际演示服务：HTTP、WebSocket、幂等发送和已读；Python 使用 SQLite。
- 三端共享消息/同步样例：超出 JS 安全整数范围的序号、未知消息类型、缓存事务失败后游标不推进、缓存恢复与用户隔离。
- Python 业务事务 Outbox：业务回滚不产生发送意图；进程重建后已提交意图可领取和确认。
- JWT 用户/服务签名密钥隔离、过期/篡改拒绝；APNs ES256 64 字节 JOSE 签名验证、请求配置、无效 token 和重试响应分类。
- iOS 示例 CocoaPods 与 SwiftPM 原生插件均完成无签名模拟器编译。
- iOS 26.5 / iPhone 17 Pro Max 模拟器集成：SQLite 关闭重开恢复、账户隔离、安装 ID 持久化、原生 MethodChannel 可用；Flutter 连接实际服务并恢复消息和提交已读。2 项通过。

复现命令：`just check`、`just package`、`just test-integration`、两个终端的 `just demo-server` / `just test-live`、macOS 下 `just build-ios` / `just test-ios <device-id>`。完整网络示例使用 APNs 关闭的临时 PostgreSQL；退出演示服务删除数据库。

GitHub [核心实现 CI](https://github.com/bitlap/polyglot-im/actions/runs/42973163968) 的 Python、Scala、Flutter、跨语言集成及 macOS iOS 编译五项全部通过。演示服务另验证默认随机端口启动、就绪探针及重启后的 Python/Dart 收发；每次演示数据库使用新的环境命名空间。

### 外部验收边界

Apple 真实 APNs 远程投递、真机前后台/强退/冷启动点击和 provisioning，仍需用户提供测试 Apple 配置及真机。模拟器编译/MethodChannel 和 APNs 请求测试不能替代 Apple 真机送达验收。服务端 APNs Provider 和原生桥接代码已实现。

宿主业务系统的业务事件生产接线、旧接口同库迁移、生产部署和负载/备份恢复演练未计为已完成。仓库提供业务事务 Outbox 与 iOS 完整示例供集成，见[业务接入](integration.md)。Android/FCM/厂商推送未实现。

## 2026-11-11 模块命名与 Flutter 单一入口

模块 ID、目录和变更记录统一为 scala-sdk、scala-server、dart-sdk、flutter-sdk、python-sdk；Scala/Python 发布名带项目名前缀，Dart/Flutter 发布名为 polyglot_im_dart_sdk / polyglot_im_flutter_sdk。Scala 包命名空间与 Python 导入名保留现有惯例。

版本仍为 0.1.0；版本脚本、锁文件、CI、源码归档和文档同步迁移。just check 与 just package 通过，共 38 项测试（Python 24、Scala 9、Dart 3、Flutter 2）。Flutter 入口测试只导入 Flutter SDK，验证核心模型重导出。另从两个源码归档解压并建立独立 Flutter 消费工程，只声明 Flutter SDK 依赖，验证传递依赖和平台适配入口；归档保留 flutter-sdk → ../dart-sdk 的相对路径。

## 2026-11-11 异步与可插拔基础

Scala 引入 Cats/Cats Effect，以 IOApp/Resource/http4s Ember 替换 JDK HTTP 入口；新增效果多态传输、凭证端口、认证装饰器与可注入就绪检查/路由。Python 增加异步 Protocol、认证装饰器与 HTTPX 可选适配器，区分借用和拥有连接池。

just check 和 just package 通过，共 37 项测试（Python 24、Scala 9、Dart 3、Flutter 1）；覆盖适配器注入、凭证替换、空凭证拒绝、取消释放、拒绝跨源/重定向、路由与就绪扩展。独立环境从 wheel 安装核心后，未安装 HTTPX 仍可导入模型/端口。新服务实际启动后 live=200、ready=503、未实现业务路径=404。

该次调整时版本保持 0.1.0，尚未改包名（后续命名迁移另见新记录）；模块称呼与发布/导入名映射见[可插拔设计](extensibility.md)。消息业务服务、数据库与推送仍未实现。

## 2026-11-11 统一版本与变更记录

全部子项目统一为 0.1.0，变更记录迁移至根目录 changelog/<版本号>.md。执行 just set-version 0.1.0 后，原生锁文件与版本检查通过；just check、just package 通过，共 23 项测试（Python 16、Dart 3、Flutter 1、Scala 3）。新增测试覆盖非法版本拒绝、只读预览、失败回滚、升级锁文件一致性以及历史/手写变更记录保留。

核对 Scala SDK/服务 JAR 与 Python wheel 的版本为 0.1.0；Dart/Flutter 归档中的 CHANGELOG.md 与根目录对应模块的 0.1.0.md 完全一致，子模块源码不保留副本。后续历史验证中的测试数量反映当时状态。

## 2026-11-11 开源许可调整

源码采用 Apache License 2.0，根目录与 Python/Dart/Flutter SDK 的 LICENSE、NOTICE 保持一致；Scala JAR/source JAR 和 Python 包元数据及归档同步携带许可声明。仓库公开不包含自动上传包注册表。本次 just package、文档/协议检查和 Ruff 检查通过；逐项核对 Scala 二进制/源码 JAR、Python wheel/sdist、Dart/Flutter 源码归档均包含一致的 LICENSE 与 NOTICE。Python wheel 的 License-Expression 与 Scala POM 的许可证名称均为 Apache-2.0。

## 2026-11-11 更名验证

项目统一更名为 polyglot-im：更新 GitHub 仓库、本地目录、SDK 包名、Scala 命名空间、配置环境变量、协议样例和文档。重新解析依赖、重建移动后的 Python 虚拟环境，并执行格式、协议、静态检查、全部测试与打包。远端结果以更名提交对应的 GitHub CI 为准。

## 2026-11-11 初始化验证

本机：macOS、实际 sbt JVM 为 Oracle JDK 21.0.2（本机启动配置覆盖）、sbt 1.10.7、Scala 3.6.3、Flutter 3.44.6、Dart 3.12.2、Python 3.13.0、uv 0.11.32、just 1.40.0。CI 另固定 JDK 17 与 Python 3.11，用于验证最低基线。

| 检查 | 结果 |
| --- | --- |
| just check | 文档与协议、Ruff、Dart/Flutter analyze、Scalafmt 和全部测试通过 |
| Python pytest | 8 项通过：样例往返、非法序号、未知类型/字段、主版本拒绝 |
| Dart test | 3 项通过：样例往返、大序号、非法序号、未知类型 |
| Flutter test | 1 项通过：生命周期挂载幂等、恢复回调及释放 |
| Scala munit | 3 项通过：样例往返、大序号、非法序号、未知类型 |
| Scala compile/package | SDK、服务端普通 JAR 构建成功 |
| Python build | wheel 与 sdist 构建成功，干净 uv 环境从 wheel 导入并解析样例通过 |
| Dart/Flutter package | 私有源码归档生成成功；不是公共注册表发布包 |
| HTTP 健康检查 | live=200、ready=503、未实现业务路径=404，JSON 响应正确 |
| 发布保护 | 三个 publish-* 入口均明确失败，未上传包 |
| 文档/Schema/OpenAPI | 相对链接、YAML、消息 Schema 与当前 OpenAPI 校验通过 |

本机实际执行组合命令 `just check package`：check 部分全部通过；初次 Flutter pub dry-run 因本地 path 依赖失败，随后将构建改成明确的私有源码归档，并重新验证构建命令。公共发布前仍须替换 path 依赖，不能把归档成功解释为 pub.dev 预检成功。

仓库首次推送后会触发 [GitHub CI](https://github.com/bitlap/polyglot-im/actions/workflows/ci.yml)，远端结果以实际 run 为准。

## 多实例与故障验证

新增独立连接池下的并发迁移、消息去重与连续序号、快照与水位一致、单调已读、全局限流、设备 token 唯一归属、票据单次消费、推送租约过期接管与旧回写隔离测试。监听断线重连、完全无监听时五秒补拉，以及 HTTP 门限取消释放均有自动化测试。MultiProcessSuite 启动两个真实 JVM，验证 A 签发 / B 建 WS / A 写入唤醒 B，强杀 A 后 B 幂等重试并继续收发。

这些测试不包含数据库主备切换、跨地域网络分区或 Apple 真机验收；容量与 HA 要求见[多实例方案](distributed.md)及[基准报告](../benchmark/README.md)。

## 2026-11-11 文字与 Unicode 表情单聊

`just check` 全部通过，共 124 项测试：Python 48、Scala SDK 14、服务端 36、Dart 12、Flutter SDK 8、Flutter 示例 1、benchmark 5。新增验证覆盖真实 PostgreSQL 的双向并发、唯一会话、幂等冲突、成员/应用隔离、双方事件、历史分页、单调已读及推送抑制；三端共享组合表情样例验证序号精度和缓存版本保护。Flutter 验证原文失败重试保留幂等键，以及后台不自动标记已读、恢复显示后合并提交。

`just test-integration` 通过：临时真实 PostgreSQL、Python/Dart HTTP 与 WebSocket、SQLite 缓存，以及 Scala SDK 单聊、表情、幂等和历史 API。`just package` 成功构建 Scala JAR、Python sdist/wheel、Dart/Flutter 源码包；`just build-ios` 成功构建无签名模拟器 Runner.app。根 README 的两行 Mermaid 架构图已实际渲染确认。没有接入图片、COS 或真实 Apple 推送服务；既有通知基准不代表新增单聊容量。
