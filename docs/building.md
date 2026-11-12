# 构建与发布

## 统一入口

just 编排原生工具，不重新实现编译器。scala-client、scala-server 和 benchmark 各自维护 build.sbt、project 与 Scalafmt 配置，根目录没有 sbt 聚合构建。服务端通过 RootProject 引用 Client 源码，benchmark 引用服务端源码。命令默认从根 justfile 所在目录执行，各 recipe 显式切换到所属语言目录。

```sh
just setup
just fmt
just check
just package
```

`setup` 按锁文件安装，不隐式升级依赖。更新 Python 依赖使用 `uv lock --project python-client`；更新 Dart 依赖在对应包目录显式运行 `dart pub upgrade`，Flutter 包使用 `flutter pub upgrade`；提交并审查锁文件变化。

## 产物

| 模块 | 命令 | 产物或检查 |
| --- | --- | --- |
| Scala Client 与服务 | just build-scala | scala-*/target/scala-3.6.3/*.jar；普通 JAR 非独立可执行包 |
| Python | just build-python | dist/python 的 wheel 与 sdist |
| Dart | just build-dart | dist/dart 下的 Client 源码归档，不上传 |
| Flutter | just build-flutter | dist/dart 下的 Client 源码归档，不上传 |
| uni-app | just build-uniapp | dist/uniapp-client 的 npm tgz，包含根 LICENSE/NOTICE，不上传 |

Python 使用 `uv build --no-sources`，验证产物不依赖开发期 source 覆盖；sdist 从根目录注入许可文件，wheel 从该 sdist 构建，源目录不维护副本。Dart/Flutter 构建为 Apache-2.0 许可的源码归档，构建时从仓库根目录注入 LICENSE 与 NOTICE，不是可直接上传注册表的发布包。分别解压到同一父目录后保留 flutter-client → ../dart-client 的本地依赖。正式发布预检需在包目录执行 dart/flutter pub publish --dry-run。Flutter 会因 path 依赖被拒绝，需在正式发布时替换；不能通过忽略错误伪装预检成功。

`examples/flutter-client` 提供仅 iOS 的示例工程。macOS + Xcode 下运行 `just build-ios` 生成无签名模拟器 App，`just example-ios` 连接本地演示服务；IPA 发行签名需要自己的 Apple 账号。

## CI

GitHub Actions 分 uni-app、Python、Scala、Flutter、跨语言集成和 iOS 编译六个 job 并行执行。各 job 调用 just 或对应原生测试、打包命令。权限为 contents: read，无发布密钥。push、PR 和 workflow_dispatch 可运行；同分支新任务取消旧任务。

- uni-app：Node 22 契约/适配器测试与 npm tgz 构建。
- Python：锁文件同步、文档/协议校验、Ruff、pytest、wheel/sdist 构建。
- Scala：Scalafmt、契约测试、编译与 JAR 打包。
- Flutter：按锁文件解析、Dart/Flutter/示例分析、单测与源码归档。
- 跨语言集成：临时 PostgreSQL/HTTP/WS 服务，Scala、Python、Dart 及 uni-app 实际接入；uni-app 从 tgz 安装到独立临时工程，验证真实 HTTP/WS。
- iOS 编译：macOS 无签名模拟器构建，独立于 Apple 远程推送真机验收。

此处并行是 CI job，不是跨语言依赖图推断。协议变更时执行全量检查，后续目录筛选不能跳过受协议影响的 Client。

## 版本管理

全部子项目采用统一版本，以根目录 [VERSION](../VERSION) 为准；Scala Client、服务端与 benchmark 的独立构建直接读取该文件。Python、Dart/Flutter、uni-app 和 OpenAPI 服务元数据由脚本同步。协议主版本仍为 1，不随 Client 小版本变化。

```sh
just set-version 0.1.2
just check-versions
# 预览升级，不修改文件，也不调用依赖工具
python3 tools/set_version.py 0.2.0 --dry-run
```

脚本使用 Python 3.11+ 标准库，实际修改时需要 PATH 中存在 uv、dart、flutter。它更新 VERSION、Python pyproject.toml、两个 Client pubspec.yaml、iOS podspec、示例 pubspec.yaml、uni-app package.json、OpenAPI 服务版本，并调用原生工具刷新 uv.lock 与 Dart/Flutter 锁文件。失败时恢复这些元数据、锁文件和本次生成的变更记录；本地工具缓存或环境如需恢复可再运行 just setup。

版本格式为 X.Y.Z，不接受预发布后缀，以避免 Python 与 Dart 版本编码差异。脚本不修改 JDK/Scala/依赖版本或 schema_version，不提交 Git、不打 tag、不上传包。版本更新不执行公共包发布。

全部变更记录集中在根目录 [changelog](../changelog/README.md)，布局为 `changelog/<版本号>.md`，每个版本仅维护一个文件，文件内按项目与各子模块分节汇总变化，不按子项目建目录。升级时只为尚不存在的版本创建一个包含模块分节的模板；已有版本内容及全部历史文件保持原样，索引保留所有版本并按版本号倒序排列。

Dart/Flutter 归档中的 CHANGELOG.md 在打包时从根目录对应版本的统一变更记录读取，包含该版本全部模块变化，不在子模块源码中维护副本。统一发布 tag 建议为 vX.Y.Z；发布自动化仍未接入。

## 发布保护

三个 `just publish-*` 命令均通过 tools/publish_guard.py 明确失败，返回未配置原因。项目源码采用 Apache License 2.0。Scala Client 的远程 publish 明确失败，本地可用 `just publish-local-scala` 发布固定版本；Scala Server 的 publish/skip 为 true，Dart publish_to 为 none；Python 不配置索引凭证。这些设置只禁用未配置的包上传，不改变开源许可证，也不是可上传的发布实现。

公共包发布尚未配置。缺少注册表命名空间验证、发布认证、Scala 签名与元数据、Dart/Flutter 可发布依赖及发布 workflow；npm 也未配置上传入口。当前只生成本地产物，或发布到本机 Ivy local。

发布通常不能覆盖同一版本；错误版本采用后续修复版本和注册表支持的撤回机制。当前尚未上线，Server 与 Client 按当前协议一起交付，数据库直接初始化当前结构。

## 并发与延迟基准

`just test` 包含基准工具单元测试；`just benchmark-smoke` 验证真实服务链路。压测参数、原始结果和报告生成见[基准模块](../benchmark/README.md)。

## 本地 Scala 依赖

`just publish-local-scala` 仅将 Scala Client 发布到当前用户的 Ivy local，不上传 Maven。
宿主使用 `"org.bitlap" %% "polyglot-im-scala-client" % "0.1.1"`；对应 Maven 坐标为 `org.bitlap:polyglot-im-scala-client_3:0.1.1`。干净环境先检出匹配版本的 IM 仓库再发布。
同一版本验证后不要覆盖为不同源码，业务仓库 CI 应固定 IM commit。

uni-app package.json 的版本同样由 VERSION 管理；`just build`/`just package` 包含 npm tgz。Node 示例与临时配置不进入包。`just test-live` 可直接运行源码示例，正式产物消费由 `just test-integration` 单独验证。
