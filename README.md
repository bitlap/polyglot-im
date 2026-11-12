# Polyglot IM

**多语言、跨平台的即时消息基础设施。** Polyglot 表示多语言，IM 表示即时消息。

面向 Scala、Flutter / Dart、Python 与 uni-app 的跨平台 IM 服务和 Client，提供文字与表情单聊、App 业务通知、可靠同步、本地缓存与 iOS 推送适配。使用 Monorepo 管理协议与实现，以 **just** 统一开发、构建和发布入口。

消息投递、发送确认、离线恢复与推送的保证统一见[消息一致性与可达性契约](docs/delivery.md)。

## 系统架构

![系统架构总览](docs/diagrams/system-overview.svg)


| 通信双方 | 发送方向 | 返回方向 |
| --- | --- | --- |
| 业务后端 ⇄ IM | 业务通知 | 事务提交确认 |
| 用户 A / B ⇄ IM | 发送、回复、补拉、ack、已读 | 提交确认、消息事件、已读回执 |
| 用户 A ⇄ 业务后端 | 业务操作 | 业务结果 |
| IM ⇄ Apple APNs | 推送请求 | 接受响应 |
| Apple APNs → 用户 B | 后台提醒，Client 再向 IM 补拉 | 单向提醒，无用户送达或已读确认 |

实例共享 PostgreSQL 主库，按接收身份将消息路由到持有连接的实例。业务后端通过通知 API 接入，用户通过 HTTP/WS 同步消息，iOS 可选接入 APNs 提醒。

蓝色大框表示独立系统，橙色大框表示独立用户；系统位于上排，用户位于下排。直角折线统一走线，实线连接系统，橙色虚线连接用户与系统，往返通道分开绘制，连线首尾均垂直于矩形边框，通信内容见图下表格，分别体现发送与返回、事件与确认。文字与表情单聊、业务通知、可靠同步与 APNs 已提供实现。用户互动通知由业务后端验证操作后发送给接收用户。单聊集成见[文字与表情](docs/direct-chat.md)，图片暂不支持。详细流程见[系统架构](docs/architecture.md)与[多实例部署](docs/distributed.md)。

## 子项目

各用户 SDK 共同支持业务通知、文字与 Unicode 表情单聊、会话与历史分页、独立未读与已读回执、HTTP/WebSocket 同步、设备注册和推送偏好。下表统一列出各子项目的定位、实现方式与平台适配；完整能力见[功能矩阵](docs/features.md)和[Client 集成总览](docs/client/README.md)。

| 子项目 | 定位 | 实现与适配 |
| --- | --- | --- |
| [Scala Client](scala-client/README.md) | Scala SDK | Cats Effect / FS2、HTTP/WebSocket、本地文件存储适配、可信业务后端的事务 Outbox |
| [Scala Server](scala-server/README.md) | IM 服务端 | PostgreSQL 持久化、HTTP/WebSocket 接口、多实例协调、鉴权与 APNs 投递 |
| [Dart Client](dart-client/README.md) | Dart SDK | 纯 Dart 消息模型与 HTTP/WebSocket；本地存储、凭证与传输端口可注入 |
| [Flutter Client](flutter-client/README.md) | Flutter SDK | 基于 Dart SDK，增加通知与单聊 UI、SQLite 存储适配、生命周期管理和 iOS APNs 桥接 |
| [Python Client](python-client/README.md) | Python SDK | async/await、HTTP/WebSocket、SQLite 存储适配、可信业务后端的事务 Outbox |
| [uni-app Client](uniapp-client/README.md) | uni-app SDK | uni.request、SocketTask 和 uni 存储适配；宿主提供页面与平台推送接入 |
| [Benchmark](benchmark/README.md) | 性能测试 | 真实 PostgreSQL 下的并发、吞吐、延迟与资源测量，以及原始数据和结果报告 |

[可运行示例](examples/README.md)按子项目集中维护；[Flutter / iOS 示例](examples/flutter-client/README.md)演示消息中心与本地缓存接入。

## Flutter App 接入入口

Flutter App 只依赖 `polyglot_im_flutter_client`，该包会引入并导出 `polyglot_im_dart_client` 的公共能力。Dart 核心保持独立，供纯 Dart 工程复用；生命周期、SQLite 与 iOS APNs 由 Flutter 层适配。用法与能力边界见[Flutter 集成文档](docs/client/flutter.md)。

## 环境

| 工具 | 基线 |
| --- | --- |
| Node.js | 22（uni-app 契约和真实网络示例） |
| just | 1.40.0（CI 固定） |
| JDK | 17+（CI 使用 17） |
| sbt / Scala | 1.10.7 / 3.6.3，由项目配置管理 |
| Flutter / Dart | 3.44.6 / 3.12.2（CI 固定 Flutter） |
| Python / uv | Python 3.11+ / uv 0.11.32（CI 固定） |

IM 服务端和可信业务后端的事务 Outbox 使用可配置的 PostgreSQL；用户端本地缓存由各 SDK 的存储适配器或宿主实现提供。服务端连接、连接池及 Python DSN 配置见[开发与配置](docs/development.md)。不要求 Redis。APNs 可显式关闭。演示服务使用仅测试依赖中的临时 PostgreSQL，无需 Docker。

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
just test-live            # 终端二：Scala / Python / Dart / uni-app 实际 HTTP/WS 收发、幂等与已读
just example-ios          # iOS 示例，读取仅用户身份的本地临时配置
```

演示数据库退出后删除，凭证写入忽略目录 `.local-demo`；每次启动使用随机端口和新测试密钥，避免客户端将旧数据库游标复用于新演示环境。不能用于生产部署。

正式服务使用 PostgreSQL 的 `snapflow` 数据库，复制 [HOCON 配置模板](config/server.example.conf) 为 `config/server.local.conf`，填写连接及三个独立随机签名密钥，然后执行 `just run-server --config "$PWD/config/server.local.conf"`。默认读取 classpath 的 `application.conf`，支持环境变量覆盖。存活探针 `/health/live`，数据库就绪 `/health/ready`。详见[配置](docs/development.md)、[架构](docs/architecture.md)及[业务接入](docs/integration.md)。

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
| `just build` / `just package` | Scala JAR、Python 包、Dart/Flutter 源码包、uni-app npm tgz |
| `just test-scala` / `just test-python` | 单独测试 Scala / Python |
| `just test-dart` / `just test-flutter` | 单独测试 Dart / Flutter |
| `just benchmark` / `just benchmark-smoke` | 真实 PostgreSQL 并发/延迟压测 / CI 冒烟 |
| `just test-benchmark` | 分位数、吞吐聚合与压测配置测试 |
| `just publish-scala` / `just publish-python` / `just publish-dart` | 发布保护入口 |

构建产物与发布配置见[构建与发布](docs/building.md)。

## 文档

- [可运行示例](examples/README.md)：各子项目的独立使用示例。
- [文档目录](docs/README.md)：完整导航。
- [统一版本](VERSION) / [变更记录](changelog/README.md)：根目录集中管理，每个版本一个文件，汇总所有模块变化。
- [技术设计](docs/design.md)：产品边界、技术选型、领域模型与状态机。
- [系统架构](docs/architecture.md)：模块职责、同步流程、存储与部署。
- [模块入口与资源管理](docs/extensibility.md)：Cats/Cats Effect、Python 异步适配、资源所有权及模块命名。
- [功能矩阵](docs/features.md)：消息功能与接口能力。
- [协议与接口](docs/protocol.md)：消息、事件、错误码和实际 API。
- [Client 集成](docs/client/README.md)：Scala、Dart、Flutter、Python 与 uni-app 的用法及可运行示例。
- [多实例部署](docs/distributed.md)。
- [消息一致性与可达性](docs/delivery.md)：至少一次、幂等范围、ack、已读、离线恢复与推送边界。
- [开发与配置](docs/development.md) / [构建与发布](docs/building.md)。
- [并发与延迟基准](benchmark/README.md)：测试口径、复现命令与实测结果。
- [验证记录](docs/verification.md)：测试与构建记录。
