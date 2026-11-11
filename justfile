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
    cd packages/dart-sdk && dart pub get --enforce-lockfile
    cd packages/flutter-sdk && flutter pub get --enforce-lockfile
    cd jvm && sbt update

fmt:
    uv run --project python-sdk --locked ruff format python-sdk tools
    uv run --project python-sdk --locked ruff check --fix python-sdk tools
    dart format packages
    cd jvm && sbt scalafmtAll scalafmtSbt

lint-python:
    uv run --project python-sdk --locked ruff check python-sdk tools
    uv run --project python-sdk --locked ruff format --check python-sdk tools

lint-dart:
    dart format --output=none --set-exit-if-changed packages
    cd packages/dart-sdk && dart analyze --fatal-infos
    cd packages/flutter-sdk && flutter analyze --no-pub --fatal-infos

lint-scala:
    cd jvm && sbt scalafmtCheckAll scalafmtSbtCheck

lint: lint-python lint-dart lint-scala

check-docs:
    uv run --project python-sdk --locked python tools/check_repository.py

test-python:
    uv run --project python-sdk --locked pytest python-sdk/tests

test-dart:
    cd packages/dart-sdk && dart test

test-flutter:
    cd packages/flutter-sdk && flutter test --no-pub

test-scala:
    cd jvm && sbt test

test: test-python test-dart test-flutter test-scala

check: check-versions check-docs lint test

build-scala:
    cd jvm && sbt compile package

build-python:
    uv build --project python-sdk --no-sources --out-dir dist/python

# SDK 包没有 App 二进制；生成保留本地依赖的源码归档。
build-dart:
    python3 tools/package_dart.py dart-sdk

build-flutter:
    python3 tools/package_dart.py flutter-sdk

build: build-scala build-python build-dart build-flutter

package: build

run-server:
    cd jvm && sbt 'scalaServer/run'

# 发布前置条件未配置；明确失败，避免误上传。
publish-scala:
    @python3 tools/publish_guard.py scala

publish-python:
    @python3 tools/publish_guard.py python

publish-dart:
    @python3 tools/publish_guard.py dart
