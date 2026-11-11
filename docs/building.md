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
| Dart | just build-dart | dist/dart 下的私有源码归档，不上传 |
| Flutter | just build-flutter | dist/dart 下的私有源码归档，不上传 |

Python 使用 `uv build --no-sources`，验证产物不依赖开发期 source 覆盖，参考 [uv 构建文档](https://docs.astral.sh/uv/guides/package/)。Dart/Flutter 当前构建为源码归档，不是可直接上传注册表的发布包。分别解压到同一父目录后保留 polyglot_im_flutter → ../polyglot_im_client 的本地依赖。正式发布预检需在包目录执行 dart/flutter pub publish --dry-run，参考 [Dart 发布说明](https://dart.dev/tools/pub/cmd/pub-lish)。Flutter 当前会因 path 依赖被拒绝，需在正式发布时替换；不能通过忽略错误伪装预检成功。

没有 Flutter App 工程，因此当前不会生成 APK、IPA 或 Web App；也没有容器镜像、安装器或已签名发布产物。后续增加示例 App 后，再单独定义平台打包命令。

## CI

GitHub Actions 分 Python、Scala、Flutter 三个 job 并行执行，全部使用 just。权限为 contents: read，无发布密钥。push、PR 和 workflow_dispatch 可运行；同分支新任务取消旧任务。

- Python：锁文件同步、文档/协议校验、Ruff、pytest、wheel/sdist 构建。
- Scala：Scalafmt、契约测试、编译与 JAR 打包。
- Flutter：按锁文件解析、Dart/Flutter 分析、单测与源码归档。

此处并行是 CI job，不是跨语言依赖图推断。协议变更时当前全量检查，后续目录筛选不能跳过受协议影响的 SDK。

## 版本与发布计划

各 SDK 独立版本，协议主版本独立管理。当前 Scala 为 0.1.0-SNAPSHOT，Python 为 0.1.0.dev0，Dart/Flutter 为 0.1.0-dev.0，均是初始化预发布版本，尚未注册包名。

建议 tag 为 `scala-vX.Y.Z`、`python-vX.Y.Z`、`dart-vX.Y.Z`、`flutter-vX.Y.Z`；服务端单独 `server-vX.Y.Z`。后续可以接 release-please，但当前没有伪造自动发布工作流。

## 当前发布保护

三个 `just publish-*` 命令均通过 tools/publish_guard.py 明确失败，返回未配置原因。Scala publish/skip 为 true，Dart publish_to 为 none；Python 不配置索引凭证。这些不是可上传的发布实现。

启用发布前必须完成：

1. 确认公开范围与许可证；私有源代码不会因为创建 GitHub 仓库自动获准发布到公共注册表。
2. 验证 Maven Central 命名空间、PyPI 和 pub.dev 包名及组织归属。
3. Dart/Flutter 移除 publish_to: none，将 Flutter 的本地 path 依赖改成已发布兼容版本。
4. 配置 Maven 签名/元数据、PyPI Trusted Publishing、pub.dev 自动发布或受控凭证。
5. 增加带环境限制的发布 workflow、版本/tag 校验、完整检查和产物记录。
6. 从干净环境验证 wheel/sdist、JAR、Dart 包消费，记录 API 兼容和回滚策略。

发布通常不能覆盖同一版本；错误版本采用后续修复版本和注册表支持的撤回机制。服务部署回滚还需验证数据库迁移向后兼容。
