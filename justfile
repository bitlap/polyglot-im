# 统一命令入口；各语言保留原生工具链。
set shell := ["bash", "-euo", "pipefail", "-c"]

# 显示可用命令。
default:
    @just --list

# 批量更新所有子项目、锁文件及版本记录；不提交、不发布。
set-version version:
    python3 tools/set_version.py {{quote(version)}}

# 检查统一版本、锁文件和变更记录。
check-versions:
    python3 tools/set_version.py --check

# 安装开发依赖；更新依赖时需显式执行对应 lock 命令。
setup:
    uv sync --project python-client --locked
    cd dart-client && dart pub get --enforce-lockfile
    cd examples/dart-client && dart pub get --enforce-lockfile
    cd flutter-client && flutter pub get --enforce-lockfile
    cd examples/flutter-client && flutter pub get --enforce-lockfile
    cd scala-client && sbt update
    cd scala-server && sbt update
    cd benchmark && sbt update
    cd examples/scala-client && sbt update
    cd examples/scala-server && sbt update

fmt:
    uv run --project python-client --locked ruff format python-client tools benchmark examples/python-client
    uv run --project python-client --locked ruff check --fix python-client tools benchmark examples/python-client
    dart format dart-client flutter-client examples/dart-client examples/flutter-client/lib examples/flutter-client/test examples/flutter-client/integration_test
    cd scala-client && sbt scalafmtAll scalafmtSbt
    cd scala-server && sbt scalafmtAll scalafmtSbt
    cd benchmark && sbt scalafmtAll scalafmtSbt
    cd examples/scala-client && sbt scalafmtAll scalafmtSbt
    cd examples/scala-server && sbt scalafmtAll scalafmtSbt

lint-python:
    uv run --project python-client --locked ruff check python-client tools benchmark examples/python-client
    uv run --project python-client --locked ruff format --check python-client tools benchmark examples/python-client

lint-dart:
    dart format --output=none --set-exit-if-changed dart-client flutter-client examples/dart-client examples/flutter-client/lib examples/flutter-client/test examples/flutter-client/integration_test
    cd dart-client && dart analyze --fatal-infos
    cd examples/dart-client && dart analyze --fatal-infos
    cd flutter-client && flutter analyze --no-pub --fatal-infos
    cd examples/flutter-client && flutter analyze --no-pub --fatal-infos

lint-scala:
    cd scala-client && sbt scalafmtCheckAll scalafmtSbtCheck
    cd scala-server && sbt scalafmtCheckAll scalafmtSbtCheck
    cd benchmark && sbt scalafmtCheckAll scalafmtSbtCheck
    cd examples/scala-client && sbt scalafmtCheckAll scalafmtSbtCheck
    cd examples/scala-server && sbt scalafmtCheckAll scalafmtSbtCheck

lint: lint-python lint-dart lint-scala

check-docs:
    uv run --project python-client --locked python tools/check_repository.py

# 核心层不可反向依赖 API、平台适配器或具体网络/数据库驱动。
check-layers:
    python3 tools/check_layers.py


test-python:
    uv run --project python-client --locked pytest python-client/tests

test-dart:
    cd dart-client && dart test

test-flutter:
    cd flutter-client && flutter test --no-pub
    cd examples/flutter-client && flutter test test --no-pub

test-scala:
    cd scala-client && sbt test
    cd scala-server && sbt test

test: test-uniapp test-python test-dart test-flutter test-scala test-benchmark test-maintenance

check: check-versions check-docs check-layers lint test

build-scala:
    cd scala-client && sbt compile package
    cd scala-server && sbt compile package
    cd benchmark && sbt compile package
    cd examples/scala-client && sbt compile
    cd examples/scala-server && sbt compile

build-python:
    uv build --project python-client --no-sources --out-dir dist/python

# Client 包没有 App 二进制；生成保留本地依赖的源码归档。
build-dart:
    python3 tools/package_dart.py dart-client

build-flutter:
    python3 tools/package_dart.py flutter-client

build: build-scala build-python build-dart build-flutter build-uniapp

package: build

run-server *args:
    cd scala-server && sbt {{quote("run " + args)}}

# 发布前置条件未配置；明确失败，避免误上传。
publish-scala:
    @python3 tools/publish_guard.py scala

publish-python:
    @python3 tools/publish_guard.py python

publish-dart:
    @python3 tools/publish_guard.py dart

# 临时 PostgreSQL + 真实 HTTP/WS，随机测试凭证写入忽略目录。
demo-server:
    cd examples/scala-server && sbt run

# 需要另一终端已启动 demo-server。
test-live:
    uv run --project python-client --locked python examples/python-client/live.py
    cd examples/dart-client && dart run live.dart
    cd examples/scala-client && sbt run
    node examples/uniapp-client/live.mjs

example-ios:
    cd examples/flutter-client && flutter pub get --enforce-lockfile
    cd examples/flutter-client && flutter run --dart-define-from-file=../../.local-demo/flutter.json

# 原生 iOS 集成测试；本地 demo 配置用于真实网络部分。
test-ios device:
    cd examples/flutter-client && flutter test integration_test/inbox_test.dart -d {{quote(device)}} --dart-define-from-file=../../.local-demo/flutter.json

build-ios:
    cd examples/flutter-client && flutter build ios --simulator --debug --no-codesign

# 自包含跨语言真实网络验收，无需手动启动演示服务。
test-integration:
    python3 tools/test_live.py

# benchmark 指标/配置单元测试；不在普通 check 中运行长时间压测。
test-benchmark:
    uv run --project python-client --locked pytest benchmark/tests

# 独立 JVM 并发基准；默认持续测量，可用 --serve-only / --config 分离发压机。
benchmark *args:
    uv run --project python-client --locked python -m benchmark.run {{args}}

# CI 功能冒烟，不覆盖正式基准结果，不设置跨机器性能门槛。
benchmark-smoke:
    uv run --project python-client --locked python -m benchmark.run --smoke --instances 2 --output /tmp/polyglot-im-benchmark-smoke.json

# 仅发布 Scala Client 到本机 Ivy，供宿主按固定版本消费；不上传注册表。
publish-local-scala:
    cd scala-client && sbt publishLocal

# 验证 uni-app 通知、单聊、实时连接、持久化、重试与鉴权契约。
test-uniapp:
    node --test uniapp-client/test/*.test.js

# 构建包含根许可的 uni-app 本地 npm 包，不上传 registry。
build-uniapp:
    python3 tools/pack_uniapp.py

# 敏感通知维护输入与事务测试；数据库测试需显式配置自有临时库。
test-maintenance:
    uv run --project python-client --locked pytest tools/test_purge_notifications.py
