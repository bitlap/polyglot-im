# polyglot-im

**多语言、跨平台的即时消息基础设施。** Polyglot 表示多语言，IM 表示即时消息。

面向 Scala、Flutter / Dart 与 Python 的跨平台 IM 服务和 SDK，承载 App 业务通知、单聊、群聊及多设备消息同步。使用 Monorepo 管理协议与实现，以 **just** 统一开发、构建和发布入口。

> 当前版本 0.1.0：已实现 PostgreSQL 通知服务、鉴权与幂等、业务/推送 Outbox、HTTP 分页/同步/已读、各端 SDK、Flutter SQLite 与消息页、WebSocket 和 iOS APNs 适配。真实 HTTP/WS、数据库测试及 iOS 模拟器编译已验证；Apple 真机远程推送与宿主生产业务迁移仍需环境验收。尚未发布公共包。详细状态见[功能矩阵](docs/features.md)。

## 目标能力

- 业务通知、单聊、群聊、系统消息与自定义消息。
- 文本、图片、语音、视频、文件、位置、名片、富文本、回复、引用、转发、表情回应与 @ 提及。
- 可靠发送、失败重试、撤回、编辑、历史分页、离线补拉、已读回执与多端同步。
- 会话未读、置顶、草稿、免打扰、归档、删除会话及本地与远端清理边界。
- 群成员与角色、邀请审批、禁言、公告、黑名单、举报及内容治理。
- 在线与输入状态、消息搜索、机器人、Webhook、APNs / FCM / 厂商推送。

这些是计划支持的功能，不代表当前 SDK 已提供对应接口。实时音视频通话与端到端加密是后续独立扩展，不计入基础 IM 完成标准。

## 当前实施优先级

| 优先级 | 范围 |
| --- | --- |
| P0 / M1 | App 业务通知、列表分页、未读/已读、可靠补拉、本地缓存、账号隔离、Scala/Python 通知发送与 Flutter 接入 |
| P1 / M2 | 前台实时更新、后台推送、通知点击/冷启动、基础静音、多设备已读同步 |
| P2 / M3 | 单聊、聊天媒体、回复引用、草稿、会话管理与私聊回执 |
| P3 / M4 | 群消息与群管理、撤回/编辑、搜索、转发、回应、机器人及其他高级功能 |

先交付 App 的通知闭环；完整 App 提醒版本以 M1 + M2 为门槛，群聊、撤回不阻塞基础版本。具体执行顺序与验收见[实施路线](docs/roadmap.md)。所有尚未实现能力仍保持规划状态。

## Flutter App 接入入口

Flutter App 只依赖 `polyglot_im_flutter_sdk`，该包会引入并导出 `polyglot_im_dart_sdk` 的公共能力。Dart 核心保持独立，供纯 Dart 工程复用；生命周期、SQLite 与 iOS APNs 由 Flutter 层适配。当前用法与能力边界见[Flutter 集成文档](docs/sdk/flutter.md)。

## 环境

| 工具 | 基线 |
| --- | --- |
| just | 1.40.0（CI 固定） |
| JDK | 17+（CI 使用 17） |
| sbt / Scala | 1.10.7 / 3.6.3，由项目配置管理 |
| Flutter / Dart | 3.44.6 / 3.12.2（CI 固定 Flutter） |
| Python / uv | Python 3.11+ / uv 0.11.32（CI 固定） |

版本沿用现有 SnapFlow 项目的兼容基线，不代表最新版。生产服务依赖 PostgreSQL；不要求 Redis。APNs 可显式关闭。演示服务使用仅测试依赖中的临时 PostgreSQL，无需 Docker。

```sh
git clone git@github.com:bitlap/polyglot-im.git
cd polyglot-im
just setup
just check
just package
```

如果系统默认 Java 低于 17，请先切换 JDK。macOS 可按需通过 Homebrew 安装 just：`brew install just`。

## 启动与演示

```sh
just demo-server          # 终端一：随机凭证、临时真实 PostgreSQL，随机本机端口
just test-live            # 终端二：Python / Dart 实际 HTTP/WS 收发、幂等与已读
just example-ios          # iOS 示例，读取仅用户身份的本地临时配置
```

演示数据库退出后删除，凭证写入忽略目录 `.local-demo`；每次启动使用随机端口和新测试密钥，避免客户端将旧数据库游标复用于新演示环境。不能用于生产部署。

正式服务先创建 PostgreSQL 数据库，按 [环境模板](config/.env.example) 提供连接及三个独立随机签名密钥，然后 `just run-server`。应用只读环境变量，不自动载入 YAML/.env。存活探针 `/health/live`，数据库就绪 `/health/ready`。详见[配置](docs/development.md)、[架构](docs/architecture.md)及[业务接入](docs/integration.md)。

## 常用命令

| 命令 | 内容 |
| --- | --- |
| `just` | 列出命令 |
| `just setup` | 按锁文件安装依赖 |
| `just set-version 0.1.0` | 批量统一所有子项目版本并刷新锁文件 |
| `just check-versions` | 检查统一版本、锁文件和版本记录 |
| `just fmt` | 格式化三种语言与构建配置 |
| `just lint` / `just test` | 静态检查 / 全部测试 |
| `just check` | 文档、协议、风格与测试检查 |
| `just build` / `just package` | Scala JAR、Python 包、Dart/Flutter 源码包 |
| `just test-scala` / `just test-python` | 单独测试 Scala / Python |
| `just test-dart` / `just test-flutter` | 单独测试 Dart / Flutter |
| `just publish-scala` / `just publish-python` / `just publish-dart` | 发布入口，初始化阶段显式禁用 |

源码采用 Apache License 2.0；SDK 尚未发布到包注册表。Dart/Flutter 构建生成源码归档供本地集成，publish_to: none 仅防止未配置的包上传；Flutter 包保留本地 path 依赖。发布前还需确认包名、Maven 命名空间与凭证配置。

## 目录

```text
polyglot-im/
├── VERSION                    # 全部子项目的统一版本
├── changelog/                 # <模块名>/<版本号>.md
├── docs/                      # 中文设计、架构、集成和运维文档
├── protocol/                  # JSON Schema、OpenAPI、共享消息样例
├── jvm/                       # Scala SDK 与持久化服务端
├── packages/dart-sdk/      # 纯 Dart HTTP/WS SDK
├── packages/flutter-sdk/     # Flutter SQLite / UI / iOS APNs
├── python-sdk/                    # Python 异步 SDK、业务 Outbox 与测试
├── config/                    # 无凭证配置示例
├── tools/                     # 文档检查、发布保护
├── justfile
└── .github/workflows/ci.yml
```

## 文档

- [文档目录](docs/README.md)：完整导航。
- [统一版本](VERSION) / [变更记录](changelog/README.md)：根目录集中管理，各模块按版本号存档。
- [技术设计](docs/design.md)：产品边界、模型、可靠性和演进。
- [系统架构](docs/architecture.md)：模块职责、同步流程、存储与部署。
- [异步与可插拔设计](docs/extensibility.md)：Cats/Cats Effect、Python 异步适配、资源所有权及模块命名。
- [功能矩阵](docs/features.md)：常见消息功能、实施阶段与验收条件。
- [协议与接口](docs/protocol.md)：消息、事件、错误码和实际 API。
- [SDK 集成](docs/sdk/README.md)：Scala、Flutter、Python 的当前用法及可运行例子。
- [开发与配置](docs/development.md) / [构建与发布](docs/building.md)。
- [验证记录](docs/verification.md)：实际执行结果和未验证范围。

## 项目约定

中文文档采用现有 `sf-app` 的简洁 README、文档索引和验证记录结构；Scala 参考 `snapflow` 的 Scala 3、Circe 与 Scalafmt 风格；通用缩进参考 `sf-admin` 的 EditorConfig。没有复制业务凭证或生产配置。

本项目公开源代码，采用 [Apache License 2.0](LICENSE)，版权声明见 [NOTICE](NOTICE)。SDK 尚未上传任何公共包仓库。
