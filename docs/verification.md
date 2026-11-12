# 验证记录与边界

## WS 专项定位

[专项原始数据与逐轮结论](../benchmark/results/ws-diagnosis-2026-11-12/README.md)保留历史 32 条未确认投递的边界，记录 128 连接重复复现、单/四进程对照、函数剖析及受控会话撤销。加速 Ping 捕获 http4s 0.23.30 的协议错误；服务升级至 0.23.38，按相同心跳压力验证，并增加真实 TCP 帧完整性回归。该实验不代替正常业务容量矩阵。同一 TCP 回归在 0.23.30 报 Unexpected rsv1 bit、在 0.23.38 通过；最终 `just check` 全部 209 项通过（Scala Server 45、benchmark 41，其余模块数量同下方记录）。

## 2026-11-12 全场景矩阵与发压端诊断

完成七场景 × 单/双实例 × 并发 1/8/32/128 × 三轮重复，共 168 轮、56 个测试单元、3,012,802 次请求/消息。原始版本为 d08edd7，WS 工具版本为 fdaf76b，已核验服务源码树一致；它们是 0.1.0 服务基线，不代表随后合并的 0.1.1 业务版本容量。每轮持续 60 秒，HTTP 预热 10 秒；固定服务总连接 8、调度 CPU 4、堆 2048 MiB。见[完整报告与逐项结论](../benchmark/README.md#实测结果)及[矩阵索引](../benchmark/results/matrix-2026-11-12/index.json)。

正式矩阵记录 32 条未确认 WS 投递，集中在双实例并发 128 的第二轮（8,595 次尝试、8,563 条已确认，末尾等待 30 秒超时）；成功延迟分位数不包含这 32 条。未确认不能证明数据库丢失。旧工具另有一次单实例 WS 收尾超时后提前退出，已保留其五轮结果和异常日志；修正后的工具保存失败计数、连接/预热错误并继续其余轮次，不丢失失败轮次。

另外完成写入、历史读取的单/四进程发压各三轮，共 12 轮，零请求错误、诊断完整。总并发 32 和服务预算相同，四进程写入吞吐为单进程的 4.26 倍（约 1,030 对 242 条/秒），历史读取为 4.56 倍（约 1,103 对 242 请求/秒），各自三轮范围不重叠。这确认这两个场景的单进程发压方式限制了测得吞吐；具体 Python/HTTP 连接池开销尚无逐函数剖析，不能据此归因所有场景或证明生产容量。

发压端、服务和 PostgreSQL 共享本机。早期 96 轮写入没有持续主机监测；单实例 WS 测量窗采到七个外部高 CPU Java 观测点，相应单元标记受干扰，限制扩容与瓶颈归因。两项发压诊断均覆盖全部测量窗且未采到外部高 CPU Java。三次早期历史尝试和旧 WS 超时/中止尝试单独归档，保留原因、已存原始数据及日志。几个运行在完成全部轮次后出现进程组退出探测 EPERM，已核验相关 JVM 退出，真实退出码保留；清理逻辑补充退出宽限等待和真实权限失败回归。

合并 0.1.1 后，`just check` 通过 205 项测试（Python 67、uni-app 5、Dart 21、Flutter Client 11、Flutter 示例 1、Scala Client 18、Scala Server 44、benchmark 38）。工具回归覆盖加权指标、原始延迟聚合、完整覆盖/重复轮次、服务源码与资源预算、未知值、主机干扰、WS 超时计数、进程退出和报告口径。`just benchmark-smoke` 完成双实例七场景、两档并发共 14 轮，零请求/连接/预热错误、零观测端错误，夹具 JVM 均已退出；其中六轮因窗口过短未形成完整持续诊断，冒烟不作为容量数据。原始结果 20 个文件的 SHA-256 全部核验，README 重生成一致。CPU 秒、I/O/WAL 累计耗时与 active 等待积分分别展示；未采集 OS CPU 调度等待、逐 SQL 与锁对象，不宣称已找到唯一根因。

## 2026-11-12 早期单场景报告展示

该阶段 benchmark README 展开当时独立 JVM 实测的完整指标，补充逐轮长尾、采样质量、逐节点资源与池、数据库事务/缓存/WAL 计数器和瓶颈证据边界。生成器从原始快照重算派生值，避免使用过期汇总；历史同 JVM 对照归档到独立报告。该阶段只展示单场景、8 并发、60 秒一轮，未重新发压；后续完整矩阵及其异常见本页上方记录。

`just check` 通过，共 166 项测试，其中 benchmark 16 项。新增回归验证原始快照重算、长尾计数、逐节点与数据库指标、缺失覆盖标记，以及重复生成报告保持使用说明且不重复追加正文。

## 2026-11-12 数据库耗时展示

补充 PostgreSQL CPU 秒数、数据块读写和 WAL 写入/同步累计耗时差值。active 等待按类型/事件对后端数量作梯形积分，仅为采样估算，排除 idle 连接；不宣称记录精确锁等待或 CPU 调度等待。字段缺失、统计重置和计数回退显示未知。已有 60 秒原始快照重新汇总后提交到[数据库观测验证报告](../benchmark/results/observability-dual-2026-11-12.md)，保留原始测量时间及代码版本，未重新运行负载。

`just check` 通过，共 165 项测试，其中 benchmark 15 项；新增覆盖 CPU 秒、累计耗时差值、统计重置/回退、active 等待积分、idle 排除、缺失字段及报告展示。

## 2026-11-12 独立 JVM 压测与瓶颈观测

压测改为每实例独立 JVM，默认业务连接总预算为 8，双实例各 4；JVM 调度 CPU 和最大堆也按总预算分配。增加单/多生产者与单/多接收者四种写入组合，默认每轮持续 60 秒，支持私有接入文件从独立发压机运行。结果保留 JVM CPU/GC、池获取耗时与占用、PostgreSQL CPU、锁等待、WAL 和 I/O 快照，并区分不完整观测。生产池创建逻辑提取为可观测组合根复用的 Resource，未修改全局限流和消息事务行为。

`just check` 通过，共 164 项测试（Python 63、Scala Client 17、Scala Server 40、Dart 19、Flutter Client 10、Flutter 示例 1、benchmark 14）。`just benchmark-smoke` 以双 JVM 完成六种 HTTP 场景和跨实例 WS 验证，无请求错误。外部接入文件路径另验证四种写入组合各持续 3 秒，确认不同 PID、连接预算、指标鉴权、凭证不进入结果、节点故障检测和退出清理；该验证仍在同一物理机执行。

另在本机以双 JVM、8 并发、4 个生产者和 16 个接收者完成单轮 60 秒持续测量，连接池为 4+4，采样完整且无请求错误。该轮仅验证长窗口执行与观测链路，不作为单/双实例容量对照或跨主机性能结论；历史同 JVM 性能报告保持原样并标明边界。真正独立发压机、固定到达率和生产容量未在本次验收中验证。

## 2026-11-12 文档与代码复核

修复 Python SQLite 连接释放、Python/Scala 设备路径编码、Dart 并发认证刷新和关闭/暂停后的迟到操作，以及 Flutter 浏览旧历史时误读新消息的问题。推送接口拒绝缺失或类型错误的 token、错误类型的 muted/preview；未指定的偏好继续保留。非法预期身份头编码返回 422，不再误报服务内部错误。Python 同步存储失败后退出 syncing，关闭期间的迟到响应不改写 idle 状态或缓存。

`just check` 通过；随后补充的 Python 和 Flutter 回归测试分别通过完整语言测试及分析检查，最终共 155 项测试通过（Python 63、Scala Client 17、Scala Server 40、Dart 19、Flutter Client 10、Flutter 示例 1、benchmark 5）。`just test-integration` 验证临时 PostgreSQL 上三种语言的真实 HTTP 收发、幂等和历史，以及 Python/Dart WebSocket 同步；`just package` 成功生成 Scala JAR、Python wheel/sdist 与 Dart/Flutter 源码归档。文档链接、Schema/OpenAPI、分层与格式检查通过。本次未执行 Apple 真机或数据库主备切换验收。

## 2026-11-12 Client 命名、首次初始化与 API 注释

四个客户端模块及示例统一改为 scala-client、dart-client、flutter-client、python-client；发布名、Dart 库入口、Flutter 原生插件、依赖锁文件、just 与 CI 同步更新。Server 移除旧包名转发、环境字段映射和未使用的环境配置工厂，使用 HOCON 与当前 schema.sql 初始化，不执行历史版本升级。

Server 表显式使用 snap_flow.im_xxx，业务 Outbox 默认使用 snap_flow.im_producer_outbox。真实 PostgreSQL 回归覆盖并发初始化、重复启动、public 同名表不影响 Server，以及 Python 在其他 search_path 下仍写入所选 schema；测试使用临时数据库，不访问 SnapFlow 生产配置。

仓库文档保留当前功能、首次接入、模块入口和资源管理。图示仅保留 SVG，直接检查正交端点、通道间距和图片唯一性。五个模块的手写类和公共方法补充中文文档注释；声明检查覆盖 Scala 312、Dart/Flutter 165、Python 85、Swift 11 项，无遗漏。

`just check` 通过，共 144 项测试：Python 59、Scala Client 16、Server 39、Dart 15、Flutter Client 9、Flutter 示例 1、benchmark 5。Dart/Flutter 的 `dart doc --dry-run` 均为零警告、零错误。`just package`、`just test-integration` 及 `just build-ios` 验证改名后的产物、临时服务三端 HTTP/WS 收发和无签名 iOS 模拟器编译；后续 API 注释与未使用工厂清理又通过上述完整检查。未发布公共包或部署生产，未进行 Apple 真机推送验收。

## 2026-11-11 子项目分层与适配器替换

五个子项目拆分 API、业务服务、领域模型、端口、配置与具体适配器，服务端增加 ServerComponents/ServerApplication 组合根。保留旧公共导入的源码兼容；Scala 兼容伴生入口使用惰性转发，避免访问一个入口时提前初始化无关模块。没有修改现有 HTTP/WS 协议、SQL 事务或默认生产存储。

`just check` 通过，共 143 项测试：Python 58、Scala Client 16、服务端 39、Dart 15、Flutter Client 9、Flutter 示例 1、benchmark 5。新增覆盖自定义传输与编解码器、作用域绑定后保留注入项、缓存提交后 ack、连接释放、借用资源不被关闭、无 MethodChannel 的推送替身，以及业务鉴权与范围策略。`just check-layers` 检查五个子项目共 137 个源码文件，并纳入 CI；反例测试验证反向依赖和具体驱动引用会被拒绝。

`just package` 构建 Scala JAR、Python wheel/sdist、Dart/Flutter 源码归档，并编译两个 Scala 示例工程；从生成的 Python wheel 在禁用 site-packages 的解释器中导入公开端口通过，不需要安装可选 HTTP/PostgreSQL 驱动。`just test-integration` 验证临时 PostgreSQL 上三端真实 HTTP/WS、单聊表情、幂等、历史及 SQLite 缓存；`just benchmark-smoke` 全部请求零错误，结果写入 /tmp/polyglot-im-benchmark-smoke.json，未覆盖正式基准。

本次验证的是端口可注入与既有后端行为。gRPC/QUIC、其他数据库和无损运行时热切换没有新增实现；替换后端必须继续满足事务、身份隔离、租约和资源所有权契约，见[分层与扩展入口](extensibility.md#分层与扩展入口)。

## 2026-11-11 示例集中与根目录许可

全部子项目删除 LICENSE/NOTICE 副本，许可正文仅根目录维护；Scala JAR、Dart/Flutter 归档和 Python sdist/wheel 从根目录注入完整内容，Python wheel 保留 License-Expression 与 License-File 元数据。逐项比对归档许可文件及统一版本记录与根目录一致。

所有可运行示例迁至 examples/<子项目名>/：Scala 演示服务与 Client 分别独立构建，Dart 示例使用独立 pubspec，Python 示例复用 Client 环境，Flutter iOS 示例移动至 examples/flutter-client；另外提供 benchmark 使用入口。just、CI、版本脚本及集成文档同步更新。

`just check package test-integration benchmark-smoke` 通过，96 项测试通过；真实服务中 Python/Dart HTTP/WS 收发及 Scala Client 发送、幂等和历史读取通过。`just build-ios` 在迁移后的示例路径成功生成无签名模拟器 Runner.app；不计为 Apple 真机推送验收。

## 2026-11-11 Scala 独立 sbt 构建

scala-client、scala-server 和 benchmark 分别在自己的目录维护 build.sbt、project/build.properties、project/plugins.sbt 与 Scalafmt 配置，根目录不再包含 sbt 配置。服务端通过 RootProject 引用 Client 源码，benchmark 引用服务端源码；各自读取根 VERSION，just 只负责切换目录编排。数据库测试夹具、演示服务与压测脚本使用所属子项目的 sbt 命令。

`just check package test-integration benchmark-smoke` 通过，共 96 项测试；全部分发产物构建成功，Python/Dart 实际 HTTP/WS 收发与压测冒烟通过。另核验三个 Scala 模块的独立配置及 JAR 许可资源，根 README 许可证栏目已移除。

## 2026-11-11 Scala 子项目平铺与 README 精简

Scala Client、服务端平铺为根目录 scala-client / scala-server；build.sbt、project 与 Scalafmt 配置移至根目录。just、版本脚本、数据库测试夹具、跨语言集成和 benchmark 均从根目录启动 sbt。演示服务默认配置目录通过测试 JVM 的根路径定位，不再依赖旧目录层级。

`just check package test-integration benchmark-smoke` 全部通过：93 项测试，Scala JAR、Python wheel/sdist、Dart/Flutter 归档构建成功，Python / Dart 实际 HTTP/WS 收发通过，benchmark 冒烟零错误。另核验三个 Scala 模块 JAR 携带一致的 LICENSE/NOTICE；许可说明只保留在根 README，子项目 README 提供功能、使用示例和根文档链接。

## 2026-11-11 PostgreSQL 业务存储与配置

Python 业务 Outbox 替换为异步 PostgreSQL，设备本地 SQLite 缓存保留。真实 PostgreSQL 测试覆盖业务回滚/提交、重复事件冲突、并发 enqueue/领取、不可重试与临时错误、取消后租约恢复、过期确认隔离和连接池所有权。测试使用独立随机 schema；本地由临时 PostgreSQL 夹具启动，CI 使用专用 PostgreSQL 16 服务。测试不读取服务端或业务生产配置。

服务端仅接受 PostgreSQL JDBC URL；连接池上限与等待超时可配置，配置测试验证默认值、覆盖及非法值启动前拒绝。Python wheel/sdist 构建通过，干净环境验证核心 wheel 不安装 PostgreSQL 驱动仍可导入，驱动保持可选。全仓检查通过共 89 项（Python 40、Scala Client 13、Scala Server 14、Dart 11、Flutter Client 6、Flutter 示例 1、benchmark 4）。

复现：`just check`。Python 单独执行 `just test-python`；可用 POLYGLOT_IM_TEST_POSTGRES_DSN 显式指定专用测试数据库，缺省启动临时嵌入式 PostgreSQL，需要 JDK/sbt。Python Client 不自动读取此测试环境变量，生产连接由宿主显式传入。

## 2026-11-11 单元测试与并发基准

新增五个 Client/服务模块的认证、同步、隔离、缓存失败、通知校验与 iOS 推送桥接测试，benchmark 增加分位数、失败率和多轮聚合测试。全仓共 82 项：Python 35、Scala Client 13、Scala Server 12、Dart 11、Flutter Client 6、Flutter 示例 1、benchmark 4。

`just check` 为统一验收入口；`just benchmark-smoke` 已在真实临时 PostgreSQL / HTTP / WebSocket 上通过，全部请求成功。正式并发级别 1/8/32/128 各 3 轮，完成 60 组、64,224 次测量且全部成功；完整实测、环境和逐条延迟样本见[基准报告](../benchmark/README.md)。持久化同步提交开启；包含推送 Outbox 写入，不包含 Apple APNs 网络投递。

## 2026-11-11 P0/P1 通知服务与全部 Client

代码版本 0.1.0，尚未上传公共包仓库。全仓检查共 56 项通过（Python 29、Scala Client 9、Scala Server 7、Dart 7、Flutter Client 3、Flutter 示例 1），另有 iOS 模拟器集成 2 项通过。下列测试使用本地测试数据库与随机演示凭证，不读取生产数据。

- 真实 PostgreSQL：并发发送、幂等冲突、跨账户隔离、稳定分页、已读单调、一次性票据、设备退出、Outbox 领取及游标过期/快照恢复。
- 真实 http4s HTTP/WebSocket + Scala Client：发送、幂等重放、同步、多设备已读和用户隔离。
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

宿主业务系统的业务事件生产接线、生产部署和负载/备份恢复演练未计为已完成。仓库提供业务事务 Outbox 与 iOS 完整示例供集成，见[业务接入](integration.md)。Android/FCM/厂商推送未实现。

## 2026-11-11 模块命名与 Flutter 单一入口

模块 ID、目录和变更记录统一为 scala-client、scala-server、dart-client、flutter-client、python-client；Scala/Python 发布名带项目名前缀，Dart/Flutter 发布名为 polyglot_im_dart_client / polyglot_im_flutter_client。Scala 包命名空间与 Python 导入名保留现有惯例。

版本仍为 0.1.0；版本脚本、锁文件、CI、源码归档和文档同步迁移。just check 与 just package 通过，共 38 项测试（Python 24、Scala 9、Dart 3、Flutter 2）。Flutter 入口测试只导入 Flutter Client，验证核心模型重导出。另从两个源码归档解压并建立独立 Flutter 消费工程，只声明 Flutter Client 依赖，验证传递依赖和平台适配入口；归档保留 flutter-client → ../dart-client 的相对路径。

## 2026-11-11 异步与可插拔基础

Scala 引入 Cats/Cats Effect，以 IOApp/Resource/http4s Ember 替换 JDK HTTP 入口；新增效果多态传输、凭证端口、认证装饰器与可注入就绪检查/路由。Python 增加异步 Protocol、认证装饰器与 HTTPX 可选适配器，区分借用和拥有连接池。

just check 和 just package 通过，共 37 项测试（Python 24、Scala 9、Dart 3、Flutter 1）；覆盖适配器注入、凭证替换、空凭证拒绝、取消释放、拒绝跨源/重定向、路由与就绪扩展。独立环境从 wheel 安装核心后，未安装 HTTPX 仍可导入模型/端口。新服务实际启动后 live=200、ready=503、未实现业务路径=404。

该次调整时版本保持 0.1.0，尚未改包名（后续命名迁移另见新记录）；模块称呼与发布/导入名映射见[可插拔设计](extensibility.md)。消息业务服务、数据库与推送仍未实现。

## 2026-11-11 统一版本与变更记录

全部子项目统一为 0.1.0，变更记录迁移至根目录 changelog/<版本号>.md。执行 just set-version 0.1.0 后，原生锁文件与版本检查通过；just check、just package 通过，共 23 项测试（Python 16、Dart 3、Flutter 1、Scala 3）。新增测试覆盖非法版本拒绝、只读预览、失败回滚、升级锁文件一致性以及历史/手写变更记录保留。

核对 Scala Client/服务 JAR 与 Python wheel 的版本为 0.1.0；Dart/Flutter 归档中的 CHANGELOG.md 与根目录对应模块的 0.1.0.md 完全一致，子模块源码不保留副本。后续历史验证中的测试数量反映当时状态。

## 2026-11-11 开源许可调整

源码采用 Apache License 2.0，根目录与 Python/Dart/Flutter Client 的 LICENSE、NOTICE 保持一致；Scala JAR/source JAR 和 Python 包元数据及归档同步携带许可声明。仓库公开不包含自动上传包注册表。本次 just package、文档/协议检查和 Ruff 检查通过；逐项核对 Scala 二进制/源码 JAR、Python wheel/sdist、Dart/Flutter 源码归档均包含一致的 LICENSE 与 NOTICE。Python wheel 的 License-Expression 与 Scala POM 的许可证名称均为 Apache-2.0。

## 2026-11-11 更名验证

项目统一更名为 polyglot-im：更新 GitHub 仓库、本地目录、Client 包名、Scala 命名空间、配置环境变量、协议样例和文档。重新解析依赖、重建移动后的 Python 虚拟环境，并执行格式、协议、静态检查、全部测试与打包。远端结果以更名提交对应的 GitHub CI 为准。

## 2026-11-11 初始化验证

本机：macOS、实际 sbt JVM 为 Oracle JDK 21.0.2（本机启动配置覆盖）、sbt 1.10.7、Scala 3.6.3、Flutter 3.44.6、Dart 3.12.2、Python 3.13.0、uv 0.11.32、just 1.40.0。CI 另固定 JDK 17 与 Python 3.11，用于验证最低基线。

| 检查 | 结果 |
| --- | --- |
| just check | 文档与协议、Ruff、Dart/Flutter analyze、Scalafmt 和全部测试通过 |
| Python pytest | 8 项通过：样例往返、非法序号、未知类型/字段、主版本拒绝 |
| Dart test | 3 项通过：样例往返、大序号、非法序号、未知类型 |
| Flutter test | 1 项通过：生命周期挂载幂等、恢复回调及释放 |
| Scala munit | 3 项通过：样例往返、大序号、非法序号、未知类型 |
| Scala compile/package | Client、服务端普通 JAR 构建成功 |
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

`just check` 全部通过，共 124 项测试：Python 48、Scala Client 14、服务端 36、Dart 12、Flutter Client 8、Flutter 示例 1、benchmark 5。新增验证覆盖真实 PostgreSQL 的双向并发、唯一会话、幂等冲突、成员/应用隔离、双方事件、历史分页、单调已读及推送抑制；三端共享组合表情样例验证序号精度和缓存版本保护。Flutter 验证原文失败重试保留幂等键，以及后台不自动标记已读、恢复显示后合并提交。

`just test-integration` 通过：临时真实 PostgreSQL、Python/Dart HTTP 与 WebSocket、SQLite 缓存，以及 Scala Client 单聊、表情、幂等和历史 API。`just package` 成功构建 Scala JAR、Python sdist/wheel、Dart/Flutter 源码包；`just build-ios` 成功构建无签名模拟器 Runner.app。根 README 的两行 Mermaid 架构图已实际渲染确认。没有接入图片、COS 或真实 Apple 推送服务；既有通知基准不代表新增单聊容量。

## 2026-11-11 HTTP 通用信封与路由前缀

`just check` 通过，共 128 项测试：Python 49、Scala Client 15、服务端 37、Dart 13、Flutter Client 8、Flutter 示例 1、benchmark 5。新增共享 HTTP 成功/错误样例与 Schema，三端契约验证 HTTP 201 解包、稳定业务错误码和无效信封拒绝；路由测试验证公共前缀挂载、未挂载和重复前缀不匹配，以及探针、鉴权和过载响应格式。

`just package`、`just test-integration`、`just benchmark-smoke` 通过。真实临时 PostgreSQL 下，三端 Client 的通知与文字单聊使用新 HTTP 信封完成收发，WebSocket 同步继续使用原有帧格式。基准客户端改读 data，冒烟请求零失败，结果写入 /tmp/polyglot-im-benchmark-smoke.json，没有覆盖正式性能结果。当前 HTTP 接口约定见[HTTP 协议](protocol.md)。

## 2026-11-11 系统与用户分框图示

7 张 Mermaid 图统一为系统在上、用户在下的网格分组布局，每个系统及用户分别分大框；系统实线、用户橙色虚线、正反箭头和规划紫色虚框分别表达交互与实现边界。逐张导出 SVG 和 PNG 并人工检查文字、框线、方向及紧凑程度；根 README 保持两排。状态图保留送达回执扩展标记，可插拔图保留单向代码依赖语义，APNs 提醒保持单向。

## 2026-11-11 折线与视觉优化

7 张 Mermaid 图配置 stepBefore 直角折线，统一浅色圆角分组、白色卡片与加粗标题；压缩标题区域，根 README 导出 PNG 约 784 × 326 像素，保持系统在上、用户在下的两排布局。逐张重新导出 SVG/PNG 并检查标题、正文、标签、双向箭头与 APNs 单向提醒；解析 SVG 验证全部 49 条边只包含水平或垂直线段。`just check-docs` 与 `git diff --check` 通过；本次仅修改文档与图示规范。

## 2026-11-11 双向通道与垂直箭头

7 张 Mermaid 图使用 stepAfter 确保跨排箭头垂直进入目标上下边；系统间发送与确认分行，相关卡片按行对齐，箭头水平进入目标左右边。用户链路返回路径平移 32px，保留两排布局及 APNs 单向提醒。逐张导出 SVG/PNG 并检查文字和通道；按实际显示偏移解析 SVG，49 条箭头全部垂直于目标矩形平直边，16 组用户与系统往返路径均无共用线段或交叉，最小间距约 29.3 SVG 单位。`just check-docs` 与 `git diff --check` 通过。

## 2026-11-11 首尾法线、图外文字与图示去重

将 7 张重复概览整理为三张独立图：README 系统总览、架构文档单聊流程、架构文档系统订阅扩展；部署与可插拔文档使用普通链接和职责说明，状态模型改为状态表。通信文字全部移至图下表格；Mermaid 源文件集中在 docs/diagrams，页面仅展示一次对应 SVG。

新增 just render-diagrams：使用原生 SVG text，按连接数量分配间隔 32 SVG 单位的端口，跨排采用 V-H-V 正交路径，往返中间通道间隔 28 SVG 单位。23 条连线全部通过首尾法线、平直边与最小通道间距检查；导出的 SVG 不包含 foreignObject，也不依赖 CSS 路径覆盖。三张最终 SVG 以网页 img 方式实际加载并截屏复核。

just check 全部通过；工具调整后 just lint-python 与 just check-docs 再次通过。内存反例验证能够拒绝重复图片、Mermaid 源文件与 SVG 不同步、起点沿边、线中文字及重叠返回路径，未为验证修改仓库文档。

## 不透明业务会话与 uni-app

新增 UserAuthenticator 和固定 HTTP 默认内省验证 App/小程序现有会话；共享身份样例验证通过，覆盖签名用户回退关闭、跨应用/权限提升拒绝、过期、撤销、重定向拒绝。uni-app 契约测试覆盖大序号、落盘后确认、缓存失败、旧账号迟到响应、游标重置及最多一次登录刷新。`just check` 完整通过；本地 npm 包包含根 LICENSE/NOTICE，使用 `just build-uniapp` 生成，未上传 registry。SnapFlow 的真实 Redis + 两个临时 PostgreSQL + IM/业务 HTTP + 小程序 SDK 的隔离端到端流程已通过，原生 App 的凭证生命周期由宿主测试覆盖。验证未向真实 Apple 设备发送推送。

## 2026-11-12 · uni-app 公共 SDK 能力对齐

Node 22 下 `just test-uniapp`（最终源码单独复跑）通过 15 项测试，覆盖四种语言共享消息/单聊/审核/HTTP 样例、大序号、未知类型、独立计数、会话 revision、历史游标、幂等请求体、Retry-After、并发 401 刷新、取消/所有权、SocketTask、存储配额失败不 ack、实时帧串行处理及拥塞恢复；补充暂停等待中的实时启动不会继续创建后台会话。

`just test-integration` 通过：临时 PostgreSQL 上 Scala/Python/Dart 真实 HTTP/WS 流程通过；uni-app 构建 npm tgz、安装到独立临时工程，以 Node fetch/WebSocket 和原子文件端口验证单聊 Unicode、重复发送去重、实时接收、已读回执、历史/会话、断线重连、暂停补拉、通知、设备偏好、持久化恢复及退出清理。Node 验收适配器不进入小程序包；仓库已将 Node 22 纳入集成 CI。

sf-uniapp 完成前后台实时/轮询生命周期与初始化竞争检查，导航/分享/资源缓存/受控消息跳转检查及 `npm run build:mp-weixin` 通过，产物为 dist/build/mp-weixin；上游 Sass 弃用提示不影响构建。没有上传或部署微信，没有验证微信真机网络域名与后台行为；原生 iOS APNs 真机投递、微信订阅消息和小程序单聊 UI 不在上述测试结果中。

业务端 `python3 scripts/verify-im-session-auth.py` 回归通过：正式 SessionManager 校验临时 Redis 不透明会话，以真实小程序 SDK 通过 HTTP 验证五类通知、作者审核读取、跨端已读、设备解绑和会话撤销。此脚本显式使用 initialize({realtime:false})，不把该项结果记作 WebSocket 或微信真机验收。

完整 `just check` 通过，涵盖统一版本、文档/协议、分层、各语言静态检查和全部测试。主分支合入 pytest 9.0.3 后同步基线并额外复跑 Python 67 项测试通过；最终 JS 15 项及文档/分层/版本检查再次通过。当前仓库只构建本地依赖，不上传公共包。

## 2026-11-12 · 通知列表布局对齐

`just check` 通过。Flutter 通知列表全部类型改用同一主题卡片：信箱图标和单行标题同一行居中，正文与分钟时间统一缩进，未读使用圆点。现有两种点击已读策略测试新增几何断言，校验信箱与标题中心的垂直坐标一致；原有刷新、分页及已读行为保留。业务审核状态和统一详情弹窗由 SnapFlow 宿主实现，SDK 不引入业务路由或状态枚举。

## 2026-11-12 · 消息正文与时间左对齐修正

消息卡片正文和分钟时间改为与卡片左边缘对齐，不再跟随标题缩进。现有点击已读策略测试同时校验图标/标题垂直中心，以及正文/时间和图标容器左边缘坐标。完整 `just check` 通过，消息协议及阅读水位保持现有语义。
