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
    uv sync --project python-sdk --locked
    cd dart-sdk && dart pub get --enforce-lockfile
    cd examples/dart-sdk && dart pub get --enforce-lockfile
    cd flutter-sdk && flutter pub get --enforce-lockfile
    cd examples/flutter-sdk && flutter pub get --enforce-lockfile
    cd scala-sdk && sbt update
    cd scala-server && sbt update
    cd benchmark && sbt update
    cd examples/scala-sdk && sbt update
    cd examples/scala-server && sbt update

fmt:
    uv run --project python-sdk --locked ruff format python-sdk tools benchmark examples/python-sdk
    uv run --project python-sdk --locked ruff check --fix python-sdk tools benchmark examples/python-sdk
    dart format dart-sdk flutter-sdk examples/dart-sdk examples/flutter-sdk/lib examples/flutter-sdk/test examples/flutter-sdk/integration_test
    cd scala-sdk && sbt scalafmtAll scalafmtSbt
    cd scala-server && sbt scalafmtAll scalafmtSbt
    cd benchmark && sbt scalafmtAll scalafmtSbt
    cd examples/scala-sdk && sbt scalafmtAll scalafmtSbt
    cd examples/scala-server && sbt scalafmtAll scalafmtSbt

lint-python:
    uv run --project python-sdk --locked ruff check python-sdk tools benchmark examples/python-sdk
    uv run --project python-sdk --locked ruff format --check python-sdk tools benchmark examples/python-sdk

lint-dart:
    dart format --output=none --set-exit-if-changed dart-sdk flutter-sdk examples/dart-sdk examples/flutter-sdk/lib examples/flutter-sdk/test examples/flutter-sdk/integration_test
    cd dart-sdk && dart analyze --fatal-infos
    cd examples/dart-sdk && dart analyze --fatal-infos
    cd flutter-sdk && flutter analyze --no-pub --fatal-infos
    cd examples/flutter-sdk && flutter analyze --no-pub --fatal-infos

lint-scala:
    cd scala-sdk && sbt scalafmtCheckAll scalafmtSbtCheck
    cd scala-server && sbt scalafmtCheckAll scalafmtSbtCheck
    cd benchmark && sbt scalafmtCheckAll scalafmtSbtCheck
    cd examples/scala-sdk && sbt scalafmtCheckAll scalafmtSbtCheck
    cd examples/scala-server && sbt scalafmtCheckAll scalafmtSbtCheck

lint: lint-python lint-dart lint-scala

check-docs:
    uv run --project python-sdk --locked python tools/check_repository.py

test-python:
    uv run --project python-sdk --locked pytest python-sdk/tests

test-dart:
    cd dart-sdk && dart test

test-flutter:
    cd flutter-sdk && flutter test --no-pub
    cd examples/flutter-sdk && flutter test test --no-pub

test-scala:
    cd scala-sdk && sbt test
    cd scala-server && sbt test

test: test-python test-dart test-flutter test-scala test-benchmark

check: check-versions check-docs lint test

build-scala:
    cd scala-sdk && sbt compile package
    cd scala-server && sbt compile package
    cd benchmark && sbt compile package
    cd examples/scala-sdk && sbt compile
    cd examples/scala-server && sbt compile

build-python:
    uv build --project python-sdk --no-sources --out-dir dist/python

# SDK 包没有 App 二进制；生成保留本地依赖的源码归档。
build-dart:
    python3 tools/package_dart.py dart-sdk

build-flutter:
    python3 tools/package_dart.py flutter-sdk

build: build-scala build-python build-dart build-flutter

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
    uv run --project python-sdk --locked python examples/python-sdk/live.py
    cd examples/dart-sdk && dart run live.dart
    cd examples/scala-sdk && sbt run

example-ios:
    cd examples/flutter-sdk && flutter pub get --enforce-lockfile
    cd examples/flutter-sdk && flutter run --dart-define-from-file=../../.local-demo/flutter.json

# 原生 iOS 集成测试；本地 demo 配置用于真实网络部分。
test-ios device:
    cd examples/flutter-sdk && flutter test integration_test/inbox_test.dart -d {{quote(device)}} --dart-define-from-file=../../.local-demo/flutter.json

build-ios:
    cd examples/flutter-sdk && flutter build ios --simulator --debug --no-codesign

# 自包含跨语言真实网络验收，无需手动启动演示服务。
test-integration:
    python3 tools/test_live.py

# benchmark 指标/配置单元测试；不在普通 check 中运行长时间压测。
test-benchmark:
    uv run --project python-sdk --locked pytest benchmark/tests

# 真实并发与延迟基准，原始结果写入 benchmark/results/latest.json。
benchmark *args:
    uv run --project python-sdk --locked python -m benchmark.run {{args}}

# CI 功能冒烟，不覆盖正式基准结果，不设置跨机器性能门槛。
benchmark-smoke:
    uv run --project python-sdk --locked python -m benchmark.run --smoke --output /tmp/polyglot-im-benchmark-smoke.json
