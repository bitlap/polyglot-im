# 验证记录与当前边界

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

## 未验证范围

PostgreSQL、Redis、WebSocket、真实聊天、消息发送与持久化、多端可靠同步、移动/桌面/Web 系统推送、外部注册表发布及容量压测均尚未实现或验证。Flutter 单元测试不是 iOS/Android 真机验证。没有向任何生产业务系统写入消息或使用其凭证。

当前机器契约只覆盖已实现消息信封及健康检查；消息类型 payload 与业务 API 的完整 Schema 将随对应阶段实现。功能状态见 [功能矩阵](features.md)。
