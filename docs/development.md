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
- Dart：纯协议放 polyglot_im_dart_client；Flutter API 放 polyglot_im_flutter_client。使用 dart format、推荐 lints，不把 Widgets 或 dart:io 引入纯 Dart 协议层。
- Python：包名 polyglot_im，四空格缩进，类型注解，Ruff 格式及检查；核心模型/端口无运行时第三方依赖；HTTPX 适配器通过 [http] 可选依赖安装。
- 协议：snake_case 字段；字符串 ID 和序号；payload 支持未知类型回退。
- 文档：中文、相对链接、仅描述当前能力、使用方式和验收边界，不链接 issue 路线图或规划；复杂流程直接维护 SVG。

样例和配置只使用虚构数据。不得提交 .env、生产数据库连接、Apple 私钥或推送令牌。

## 服务配置

服务端使用 HOCON（`.conf`）与 PureConfig 类型化读取。配置只在组合根加载，再注入数据库、鉴权、HTTP 与推送适配器。完整模板见 [server.example.conf](../config/server.example.conf)。

```sh
cp config/server.example.conf config/server.local.conf
# 编辑连接、应用身份和三个独立随机密钥后，从仓库根目录启动
just run-server --config "$PWD/config/server.local.conf"
```

配置文件选择优先级为 `--config <路径>` > JVM `-Dconfig.file=<路径>` > `POLYGLOT_IM_CONFIG`。未指定文件时加载 classpath `application.conf`（也可用 `-Dconfig.resource=<名称>` 替换）。`scala-server/src/main/resources/application.conf` 可直接填写部署配置，`reference.conf` 提供公共默认值；包含私钥或部署凭证的文件应在仓库外维护，或使用已忽略的 `*.local.conf`。

字段覆盖顺序：JVM `-Dpolyglot-im.*` 属性 > 应用配置文件 > `reference.conf`。支持 HOCON `include`、`${...}` 替换、`${?ENV}` 可选环境引用和时长（如 `3 seconds`）。不会自动加载 `.env`。`just run-server` 会进入 `scala-server`，建议传入绝对配置路径；APNs `key-file` 相对指定配置文件目录解析，无指定文件时相对工作目录。

例如在 `scala-server` 目录运行：

```sh
sbt -Dconfig.file=/absolute/path/server.conf -Dpolyglot-im.server.port=8091 run
```

以下字段均位于 `polyglot-im` 下。环境变量需要在 HOCON 中通过 `${?ENV}` 显式引用，不再维护旧环境变量到配置字段的隐式映射。

| HOCON 字段 | 作用与默认值 |
| --- | --- |
| server.host / port | 默认 127.0.0.1:8090 |
| server.max-in-flight | HTTP 在途请求上限，默认 256，满载 503 |
| database.url / user / password | PostgreSQL JDBC 连接；URL、用户必填，密码默认空 |
| database.pool-size | 连接池上限，默认 8，须大于 0 |
| database.connection-timeout | 等待连接超时，默认 10 seconds，最小 250ms |
| database.statement-timeout / lock-timeout | 默认 15 / 3 seconds，均大于 0，锁等待不得超过语句超时 |
| auth.app-id | 单部署应用命名空间，必填 |
| auth.issuer | 默认 polyglot-im |
| auth.user-jwt-secret / service-jwt-secret / cursor-secret | JWT 模式三个不同随机密钥，各至少 32 字节；内省模式仅 service/cursor 必需，拒绝模板占位符 |
| auth.introspection-url | 可选业务登录校验端点，HTTPS；本地 HTTP 仅限 loopback |
| apns.enabled / sandbox | 均默认 false；sandbox=true 为开发通道 |
| apns.team-id / key-id / topic / key-file | 开启 APNs 时全部必填 |
| rate-limits.user-per-minute / service-per-minute | 默认 600 / 120，均须为正数；跨实例共享 |
| push.parallelism | 推送并发数，默认 8，范围 1..256 |
| push.lease-duration / job-timeout / poll-interval | 默认 60 / 20 / 1 seconds，租约比任务超时至少长 5 秒 |

`POLYGLOT_IM_BUSINESS_DATABASE_DSN` 由 Python 宿主显式传入业务 Outbox，服务端不读取此变量。配置解析失败、未知字段、缺失必填项和非法取值会在连接数据库前报错；错误和配置对象打印不输出连接凭证或签名密钥。

服务端和 Python 业务 Outbox 的部署数据库为 `snapflow`，配置模板使用 `jdbc:postgresql://127.0.0.1:5432/snapflow`；Python DSN 使用 `postgresql://user:password@127.0.0.1:5432/snapflow`。主机、端口、登录用户与密码按部署环境配置，数据库 URL 仍须显式提供。Server 表显式使用 `snap_flow.im_xxx`，与 SnapFlow 的 schema 一致；连接指定数据库后初始化 schema 与当前 IM 表，不自动创建数据库，不升级旧表。

服务端仅接受 PostgreSQL JDBC URL，TLS 等驱动选项可放在 URL 查询参数中。Python 业务数据库使用 PostgreSQL DSN，设备本地离线缓存保留 SQLite；数据库凭证不进入移动 App。缺少必要数据库/签名配置或池参数非法时启动失败。只做本地演示时用 `just demo-server`，临时 PostgreSQL 与随机密钥不依赖 Docker；只绑定 loopback 随机端口，退出后数据库删除。重启后重新读取生成的配置、重建客户端；不要继续使用上次编译的演示地址。配置写入 .local-demo，不能用作生产环境。开启 APNs 的正式服务用 `just run-server`。

多实例要求统一身份与签名配置，共享 PostgreSQL 写主库，每实例额外一条 LISTEN 连接；负载均衡及数据库 HA 要求见[多实例部署](distributed.md)。

## 客户端身份与存储约定

缓存命名空间使用 environment + app_id + user_id。device_id 是应用安装身份，不采用不可重置的硬件标识。刷新 token 通过 tokenProvider 注入；并发刷新策略由客户端/宿主协调，不在 Client 日志输出。

账号/服务环境切换时先撤销当前任务代次、断开连接、取消发送与同步、释放数据库，再打开新命名空间。每个异步回调验证所属代次；旧账号事件不得影响新账号列表、红点或导航。

## 协议变更流程

1. 在对应 issue 中明确功能、权限、失败与多端语义。
2. 更新 Schema / OpenAPI 与共享样例；增加合法及非法输入契约用例。
3. 修改 Scala、Dart、Python 编解码及契约测试。
4. 运行 just check；尚未上线期间，Server 与 Client 按同一份当前协议同步更新。
5. 更新集成说明、根目录 changelog/<版本号>.md、当前使用说明与实际验证记录。

## 统一版本与变更记录

所有子项目使用根目录 VERSION，初始值为 0.1.0，当前值以文件为准。运行 `just set-version <X.Y.Z>` 批量更新版本、原生锁文件和各模块变更记录索引；不要分散手动修改某个 Client 版本。运行 `just check-versions` 校验一致性。具体行为见[构建与发布](building.md)。

变更记录只放根目录 changelog/<版本号>.md；每个版本一个文件，文件内按模块归类；子目录不维护 CHANGELOG.md。历史版本记录保留，变更记录索引由脚本管理。

## 异步与模块边界

`just check-layers` 校验五个子项目的层间依赖；新适配器实现 ports，核心层不得反向导入 api、adapters/infrastructure 或具体驱动。目录职责与注入入口见[分层与扩展](extensibility.md#分层与扩展入口)。

Scala 采用 Cats/Cats Effect 的 F[_] + Resource，IO 只在组合根落地；Python 采用 async def + Protocol + 异步上下文管理。网络、凭证、就绪检查已经可以注入；存储、WS 与 APNs 适配器已提供，不以空接口冒充已完成能力。模块称呼和包名映射见[异步与可插拔设计](extensibility.md)。

## 并发与延迟基准

`just test` 包含 benchmark 指标单元测试。`just benchmark-smoke` 验证独立压测实例；`just benchmark` 使用独立 JVM 测量并发 1/8/32/128 的 HTTP 吞吐和延迟、WebSocket 到达延迟，默认每组持续 60 秒并重复 3 轮；支持固定资源总预算及独立发压机。原始结果写入 benchmark/results/latest.json；用 `uv run --project python-client --locked python -m benchmark.report benchmark/results/latest.json` 更新报告。环境、持久化参数和边界见[基准模块](../benchmark/README.md)。完整压测不设跨机器性能阈值。

## SVG 图示规范

仅维护 docs/diagrams/*.svg；README 展示系统总览，架构文档展示单聊流程。每张图只嵌入一次，其他文档链接到对应章节。

系统在上、用户在下并分别分框；系统实线、用户与系统橙色虚线。往返通道分开，首尾垂直于矩形平直边并避开圆角；通信说明放在图下表格，APNs 提醒保持单向。

修改 SVG 后检查画面与文字，再运行 just check-docs 校验正交通道、端点、间距、图外文字和图片唯一性。图示使用原生 SVG text，不依赖 foreignObject 或 CSS 路径覆盖。
