# 构建与发布

## 统一入口

just 编排原生工具，不重新实现编译器。命令默认从根 justfile 所在目录执行，各 recipe 显式切换到所属语言目录。参考 [just 工作目录说明](https://just.systems/man/en/working-directory.html)。

```sh
just setup
just fmt
just check
just package
```

`setup` 按锁文件安装，不隐式升级依赖。更新 Python 依赖使用 `uv lock --project python`；更新 Dart 依赖在对应包目录显式运行 `dart pub upgrade`，Flutter 包使用 `flutter pub upgrade`；提交并审查锁文件变化。

## 产物

| 模块 | 命令 | 产物或检查 |
| --- | --- | --- |
| Scala SDK 与服务 | just build-scala | jvm/*/target/scala-3.6.3/*.jar；普通 JAR 非独立可执行包 |
| Python | just build-python | dist/python 的 wheel 与 sdist |
| Dart | just build-dart | dist/dart 下的SDK 源码归档，不上传 |
| Flutter | just build-flutter | dist/dart 下的SDK 源码归档，不上传 |

Python 使用 `uv build --no-sources`，验证产物不依赖开发期 source 覆盖，参考 [uv 构建文档](https://docs.astral.sh/uv/guides/package/)。Dart/Flutter 当前构建为 Apache-2.0 许可的源码归档，包含 LICENSE 与 NOTICE，不是可直接上传注册表的发布包。分别解压到同一父目录后保留 polyglot_im_flutter → ../polyglot_im_client 的本地依赖。正式发布预检需在包目录执行 dart/flutter pub publish --dry-run，参考 [Dart 发布说明](https://dart.dev/tools/pub/cmd/pub-lish)。Flutter 当前会因 path 依赖被拒绝，需在正式发布时替换；不能通过忽略错误伪装预检成功。

没有 Flutter App 工程，因此当前不会生成 APK、IPA 或 Web App；也没有容器镜像、安装器或已签名发布产物。后续增加示例 App 后，再单独定义平台打包命令。

## CI

GitHub Actions 分 Python、Scala、Flutter 三个 job 并行执行，全部使用 just。权限为 contents: read，无发布密钥。push、PR 和 workflow_dispatch 可运行；同分支新任务取消旧任务。

- Python：锁文件同步、文档/协议校验、Ruff、pytest、wheel/sdist 构建。
- Scala：Scalafmt、契约测试、编译与 JAR 打包。
- Flutter：按锁文件解析、Dart/Flutter 分析、单测与源码归档。

此处并行是 CI job，不是跨语言依赖图推断。协议变更时当前全量检查，后续目录筛选不能跳过受协议影响的 SDK。

## 版本与发布计划

全部子项目采用统一版本，初始化版本为 **0.1.0**，根目录 [VERSION](../VERSION) 为统一版本来源；Scala 根项目、SDK 与服务端直接读取该文件。Python、Dart/Flutter 和 OpenAPI 服务元数据由脚本同步。协议主版本仍为 1，不随 SDK 小版本变化。

```sh
just set-version 0.1.0
just check-versions
# 预览升级，不修改文件，也不调用依赖工具
python3 tools/set_version.py 0.2.0 --dry-run
```

脚本使用 Python 3.11+ 标准库，实际修改时需要 PATH 中存在 uv、dart、flutter。它更新 VERSION、Python pyproject.toml、两个 pubspec.yaml、OpenAPI 服务版本，并调用原生工具刷新 uv.lock 与 Dart/Flutter 锁文件。失败时恢复这些元数据、锁文件和本次生成的变更记录；本地工具缓存或环境如需恢复可再运行 just setup。

版本格式为 X.Y.Z，当前不接受预发布后缀，以避免 Python 与 Dart 版本编码差异。脚本不修改 JDK/Scala/依赖版本或 schema_version，不提交 Git、不打 tag、不上传包。更改版本不代表规划功能已完成，初始化版本 0.1.0 仍未发布。

全部变更记录集中在根目录 [changelog](../changelog/README.md)，布局为 `changelog/<模块名>/<版本号>.md`。仓库本身及五个子模块各有记录目录。升级时为尚不存在的版本创建“待补充”文件，保留所有历史和已填写内容；发布前逐模块补齐真实变更。

Dart/Flutter 归档中的 CHANGELOG.md 在打包时从根目录对应版本记录读取，不在子模块源码中维护副本。统一发布 tag 建议为 vX.Y.Z；发布自动化仍未接入。

## 当前发布保护

三个 `just publish-*` 命令均通过 tools/publish_guard.py 明确失败，返回未配置原因。项目源码采用 Apache License 2.0。Scala publish/skip 为 true，Dart publish_to 为 none；Python 不配置索引凭证。这些设置只禁用未配置的包上传，不改变开源许可证，也不是可上传的发布实现。

启用发布前必须完成：

1. 保持 Apache-2.0 元数据，并确保 SDK 归档、Python wheel/sdist 和 Scala JAR 包含 LICENSE 与 NOTICE；源码公开不自动配置包注册表发布。
2. 验证 Maven Central 命名空间、PyPI 和 pub.dev 包名及组织归属。
3. Dart/Flutter 移除 publish_to: none，将 Flutter 的本地 path 依赖改成已发布兼容版本。
4. 配置 Maven 签名/元数据、PyPI Trusted Publishing、pub.dev 自动发布或受控凭证。
5. 增加带环境限制的发布 workflow、版本/tag 校验、完整检查和产物记录。
6. 从干净环境验证 wheel/sdist、JAR、Dart 包消费，记录 API 兼容和回滚策略。

发布通常不能覆盖同一版本；错误版本采用后续修复版本和注册表支持的撤回机制。服务部署回滚还需验证数据库迁移向后兼容。
