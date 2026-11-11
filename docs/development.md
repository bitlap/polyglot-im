# 开发与配置

## 工具链

使用 JDK 17+、sbt 1.10.7、Scala 3.6.3、Flutter 3.44.6 / Dart 3.12.2、Python 3.11+、uv 0.11.32、just 1.40.0。CI 固定版本；本机版本变化须通过检查后再更新基线。

```sh
just setup
just check
```

just 不安装全局工具链，不是跨语言依赖解析器。Python 使用 uv.lock，Dart/Flutter 各自提交 pubspec.lock，Scala 使用固定直接依赖版本与 sbt 版本。Scala 未提供完整传递依赖锁，不能声称全仓已达到密闭可复现构建。

## 目录与代码风格

- Scala：包名 `polyglot.im`，两空格缩进，Scala 3；Circe 处理 JSON，Scalafmt 120 列。组织标识暂用现有后端的 `cn.dreamylost`，Maven 发布前单独验证归属。
- Dart：纯协议放 polyglot_im_dart_sdk；Flutter API 放 polyglot_im_flutter_sdk。使用 dart format、推荐 lints，不把 Widgets 或 dart:io 引入纯 Dart 协议层。
- Python：包名 polyglot_im，四空格缩进，类型注解，Ruff 格式及检查；核心模型/端口无运行时第三方依赖；HTTPX 适配器通过 [http] 可选依赖安装。
- 协议：snake_case 字段；字符串 ID 和序号；payload 支持未知类型回退。
- 文档：中文、相对链接、明确已实现与规划；复杂流程使用 Mermaid。

样例和配置只使用虚构数据。不得提交 .env、生产数据库连接、Apple 私钥或推送令牌。

## 服务配置

服务端使用 HOCON（`.conf`）与 PureConfig 类型化读取。配置只在组合根加载，再注入数据库、鉴权、HTTP 与推送适配器。完整模板见 [server.example.conf](../config/server.example.conf)。

```sh
cp config/server.example.conf config/server.local.conf
# 编辑连接、应用身份和三个独立随机密钥后，从仓库根目录启动
just run-server --config "$PWD/config/server.local.conf"
```

配置文件选择优先级为 `--config <路径>` > JVM `-Dconfig.file=<路径>` > `POLYGLOT_IM_CONFIG`。未指定文件时加载 classpath `application.conf`（也可用 `-Dconfig.resource=<名称>` 替换）。`scala-server/src/main/resources/application.conf` 可直接填写部署配置，`reference.conf` 提供公共默认值；包含私钥或部署凭证的文件应在仓库外维护，或使用已忽略的 `*.local.conf`。

字段覆盖顺序：JVM `-Dpolyglot-im.*` 属性 > 已设置的兼容环境变量 > 应用配置文件 > `reference.conf`。支持 HOCON `include`、`${...}` 替换、`${?ENV}` 可选环境引用和时长（如 `3 seconds`）。不会自动加载 `.env`。`just run-server` 会进入 `scala-server`，建议传入绝对配置路径；APNs `key-file` 相对指定配置文件目录解析，无指定文件时相对工作目录。

例如在 `scala-server` 目录运行：

```sh
sbt -Dconfig.file=/absolute/path/server.conf -Dpolyglot-im.server.port=8091 run
```

以下字段均位于 `polyglot-im` 下。兼容环境变量完整列表见 [.env.example](../config/.env.example)，可逐项覆盖文件，不再要求所有配置通过环境变量传递。

| HOCON 字段 | 作用与默认值 |
| --- | --- |
| server.host / port | 默认 127.0.0.1:8090 |
| server.max-in-flight | HTTP 在途请求上限，默认 256，满载 503 |
| database.url / user / password | PostgreSQL JDBC 连接；URL、用户必填，密码默认空 |
| database.pool-size | 连接池上限，默认 8，须大于 0 |
| database.connection-timeout | 等待连接超时，默认 10 seconds，最小 250ms；兼容环境变量仍使用毫秒 |
| database.statement-timeout / lock-timeout | 默认 15 / 3 seconds，均大于 0，锁等待不得超过语句超时 |
| auth.app-id | 单部署应用命名空间，必填 |
| auth.issuer | 默认 polyglot-im |
| auth.user-jwt-secret / service-jwt-secret / cursor-secret | 三个不同随机密钥，各至少 32 字节，拒绝模板占位符 |
| auth.introspection-url | 可选业务登录校验端点，HTTPS；本地 HTTP 仅限 loopback |
| apns.enabled / sandbox | 均默认 false；sandbox=true 为开发通道 |
| apns.team-id / key-id / topic / key-file | 开启 APNs 时全部必填 |
| rate-limits.user-per-minute / service-per-minute | 默认 600 / 120，均须为正数；跨实例共享 |
| push.parallelism | 推送并发数，默认 8，范围 1..256 |
| push.lease-duration / job-timeout / poll-interval | 默认 60 / 20 / 1 seconds，租约比任务超时至少长 5 秒 |

`POLYGLOT_IM_BUSINESS_DATABASE_DSN` 由 Python 宿主显式传入业务 Outbox，服务端不读取此变量。配置解析失败、未知字段、缺失必填项和非法取值会在连接数据库前报错；错误和配置对象打印不输出连接凭证或签名密钥。

服务端和 Python 业务 Outbox 的部署数据库为 `snapflow`，配置模板使用 `jdbc:postgresql://127.0.0.1:5432/snapflow`；Python DSN 使用 `postgresql://user:password@127.0.0.1:5432/snapflow`。主机、端口、登录用户与密码按部署环境配置，数据库 URL 仍须显式提供。IM 迁移表使用 `im_` 前缀；连接到指定数据库后只执行 IM 表迁移，不自动创建数据库。

服务端仅接受 PostgreSQL JDBC URL，TLS 等驱动选项可放在 URL 查询参数中。Python 业务数据库使用 PostgreSQL DSN，设备本地离线缓存保留 SQLite；数据库凭证不进入移动 App。缺少必要数据库/签名配置或池参数非法时启动失败。只做本地演示时用 `just demo-server`，临时 PostgreSQL 与随机密钥不依赖 Docker；只绑定 loopback 随机端口，退出后数据库删除。重启后重新读取生成的配置、重建客户端；不要继续使用上次编译的演示地址。配置写入 .local-demo，不能用作生产环境。开启 APNs 的正式服务用 `just run-server`。

多实例要求统一身份与签名配置，共享 PostgreSQL 写主库，每实例额外一条 LISTEN 连接；负载均衡及数据库 HA 要求见[多实例部署](distributed.md)。

## 客户端身份与存储约定

缓存命名空间使用 environment + app_id + user_id。device_id 是应用安装身份，不采用不可重置的硬件标识。刷新 token 通过 tokenProvider 注入；并发刷新策略由客户端/宿主协调，不在 SDK 日志输出。

账号/服务环境切换时先撤销当前任务代次、断开连接、取消发送与同步、释放数据库，再打开新命名空间。每个异步回调验证所属代次；旧账号事件不得影响新账号列表、红点或导航。

## 协议变更流程

1. 在设计和功能矩阵中明确功能、权限、失败与多端语义。
2. 更新 Schema / OpenAPI 与共享样例；增加非法输入和兼容性用例。
3. 修改 Scala、Dart、Python 编解码及契约测试。
4. 运行 just check；协议增量必须考虑旧 SDK 行为。
5. 更新集成说明、根目录 changelog/<版本号>.md、迁移说明与实际验证记录。

## 统一版本与变更记录

所有子项目使用根目录 VERSION，初始值为 0.1.0，当前值以文件为准。运行 `just set-version <X.Y.Z>` 批量更新版本、原生锁文件和各模块变更记录索引；不要分散手动修改某个 SDK 版本。运行 `just check-versions` 校验一致性。具体行为见[构建与发布](building.md)。

变更记录只放根目录 changelog/<版本号>.md；每个版本一个文件，文件内按模块归类；子目录不维护 CHANGELOG.md。历史版本记录保留，变更记录索引由脚本管理。

## 异步与模块边界

Scala 采用 Cats/Cats Effect 的 F[_] + Resource，IO 只在组合根落地；Python 采用 async def + Protocol + 异步上下文管理。网络、凭证、就绪检查已经可以注入；存储、WS 与 APNs 适配器已提供，不以空接口冒充已完成能力。模块称呼和包名映射见[异步与可插拔设计](extensibility.md)。

## 并发与延迟基准

`just test` 包含 benchmark 指标单元测试。`just benchmark-smoke` 验证独立压测实例；`just benchmark` 测量并发 1/8/32/128 的 HTTP 吞吐和延迟、WebSocket 到达延迟，默认每组 3 轮。原始结果写入 benchmark/results/latest.json；用 `uv run --project python-sdk --locked python -m benchmark.report benchmark/results/latest.json` 更新报告。环境、持久化参数和边界见[基准模块](../benchmark/README.md)。完整压测不设跨机器性能阈值。

## Mermaid 图示规范

- 使用网格分组固定上下布局，每个独立系统一个蓝色大框，每个独立用户一个橙色大框；系统在上，用户在下，实例、数据库与 Worker 列在所属系统内。
- 系统间交互使用实线，用户与系统交互使用橙色虚线；有请求与响应、事件与 ack、发送与已读回执时，分别画出两个方向的箭头。APNs 向用户提醒保持单向，其接受响应只返回 IM。
- 双向运行时交互不改变单向代码依赖；编译依赖与装配关系在框内单独标注。规划系统使用紫色虚框并写明扩展，框内规划步骤注明扩展，不能只靠线型区分实现状态。
- 使用 Mermaid block-beta 网格布局，在 init 中设置 flowchart.curve 为 stepAfter，所有连线使用直角折线；同排系统的标题和内容行对齐，发送与确认分别连接不同的矩形行；跨排用户链路连接卡片边界，返回路径通过 themeCSS 中按边 ID 匹配的 translateX 平移 32px，形成独立通道。平移后端点必须仍落在目标边的平直区域，水平箭头进入左右边，垂直箭头进入上下边，避免穿过正文或指向圆角。统一浅蓝系统框、浅橙用户框、圆角与白色内容卡片；标题加粗；系统间往返标签分别标注，跨排标签保留请求与事件语义并避免叠字。双向链路显式声明正反两个方向。根 README 保持上下两排紧凑布局；细节图按发送、接收、持久化与确认边界说明步骤。
- 修改后逐张导出 SVG 与 PNG，检查分组、上下位置、双向箭头、往返通道间距、箭头与目标边垂直、文字重叠和规划标记；中文文档及链接通过 just check-docs。
