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

程序读取环境变量，不自动加载 .env/YAML。完整配置模板见 [config/.env.example](../config/.env.example)，说明索引见 [server.example.yaml](../config/server.example.yaml)。

| 配置 | 作用 |
| --- | --- |
| POLYGLOT_IM_HOST / PORT | 默认 127.0.0.1:8090 |
| POLYGLOT_IM_DATABASE_URL / USER / PASSWORD | PostgreSQL JDBC 连接；URL/用户必填 |
| POLYGLOT_IM_DATABASE_POOL_SIZE | 数据库连接池上限，默认 8，须大于 0 |
| POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS | 等待连接超时，默认 10000ms，最小 250ms |
| POLYGLOT_IM_BUSINESS_DATABASE_DSN | Python 宿主显式读取并传给业务 Outbox；服务端不读取此变量 |
| POLYGLOT_IM_APP_ID | 单部署的应用命名空间，必填 |
| POLYGLOT_IM_USER_JWT_SECRET / SERVICE_JWT_SECRET | 两个不同随机密钥，各至少 32 字节 |
| POLYGLOT_IM_CURSOR_SECRET | 游标签名随机密钥，至少 32 字节 |
| POLYGLOT_IM_JWT_ISSUER | 默认 polyglot-im |
| POLYGLOT_IM_INTROSPECTION_URL | 可选的现有业务登录校验端点 |
| POLYGLOT_IM_APNS_ENABLED | true 显式开启，缺省关闭 |
| POLYGLOT_IM_APNS_TEAM_ID / KEY_ID / TOPIC / KEY_FILE | 开启 APNs 必填 |
| POLYGLOT_IM_APNS_SANDBOX | true 使用开发通道，其他为发布通道 |

服务端仅接受 PostgreSQL JDBC URL，TLS 等驱动选项可放在 URL 查询参数中。Python 业务数据库使用 PostgreSQL DSN，设备本地离线缓存保留 SQLite；数据库凭证不进入移动 App。缺少必要数据库/签名配置或池参数非法时启动失败。只做本地演示时用 `just demo-server`，临时 PostgreSQL 与随机密钥不依赖 Docker；只绑定 loopback 随机端口，退出后数据库删除。重启后重新读取生成的配置、重建客户端；不要继续使用上次编译的演示地址。配置写入 .local-demo，不能用作生产环境。开启 APNs 的正式服务用 `just run-server`。

## 客户端身份与存储约定

缓存命名空间使用 environment + app_id + user_id。device_id 是应用安装身份，不采用不可重置的硬件标识。刷新 token 通过 tokenProvider 注入；并发刷新策略由客户端/宿主协调，不在 SDK 日志输出。

账号/服务环境切换时先撤销当前任务代次、断开连接、取消发送与同步、释放数据库，再打开新命名空间。每个异步回调验证所属代次；旧账号事件不得影响新账号列表、红点或导航。

## 协议变更流程

1. 在设计和功能矩阵中明确功能、权限、失败与多端语义。
2. 更新 Schema / OpenAPI 与共享样例；增加非法输入和兼容性用例。
3. 修改 Scala、Dart、Python 编解码及契约测试。
4. 运行 just check；协议增量必须考虑旧 SDK 行为。
5. 更新集成说明、根目录 changelog/<模块名>/<版本号>.md、迁移说明与实际验证记录。

## 统一版本与变更记录

所有子项目使用根目录 VERSION，初始值为 0.1.0，当前值以文件为准。运行 `just set-version <X.Y.Z>` 批量更新版本、原生锁文件和各模块变更记录索引；不要分散手动修改某个 SDK 版本。运行 `just check-versions` 校验一致性。具体行为见[构建与发布](building.md)。

变更记录只放根目录 changelog/<模块名>/<版本号>.md；子目录不维护 CHANGELOG.md。历史版本记录保留，变更记录索引由脚本管理。

## 异步与模块边界

Scala 采用 Cats/Cats Effect 的 F[_] + Resource，IO 只在组合根落地；Python 采用 async def + Protocol + 异步上下文管理。网络、凭证、就绪检查已经可以注入；存储、WS 与 APNs 适配器已提供，不以空接口冒充已完成能力。模块称呼和包名映射见[异步与可插拔设计](extensibility.md)。

## 并发与延迟基准

`just test` 包含 benchmark 指标单元测试。`just benchmark-smoke` 验证独立压测实例；`just benchmark` 测量并发 1/8/32/128 的 HTTP 吞吐和延迟、WebSocket 到达延迟，默认每组 3 轮。原始结果写入 benchmark/results/latest.json；用 `uv run --project python-sdk --locked python -m benchmark.report benchmark/results/latest.json` 更新报告。环境、持久化参数和边界见[基准模块](../benchmark/README.md)。完整压测不设跨机器性能阈值。
