# 统一命令入口；各语言保留原生工具链。
set shell := ["bash", "-euo", "pipefail", "-c"]

# 显示可用命令。
default:
    @just --list

# 安装开发依赖；更新依赖时需显式执行对应 lock 命令。
setup:
    uv sync --project python --locked
    cd packages/sf_im_client && dart pub get --enforce-lockfile
    cd packages/sf_im_flutter && flutter pub get --enforce-lockfile
    cd jvm && sbt update

fmt:
    uv run --project python --locked ruff format python tools
    uv run --project python --locked ruff check --fix python tools
    dart format packages
    cd jvm && sbt scalafmtAll scalafmtSbt

lint-python:
    uv run --project python --locked ruff check python tools
    uv run --project python --locked ruff format --check python tools

lint-dart:
    dart format --output=none --set-exit-if-changed packages
    cd packages/sf_im_client && dart analyze --fatal-infos
    cd packages/sf_im_flutter && flutter analyze --no-pub --fatal-infos

lint-scala:
    cd jvm && sbt scalafmtCheckAll scalafmtSbtCheck

lint: lint-python lint-dart lint-scala

check-docs:
    uv run --project python --locked python tools/check_repository.py

test-python:
    uv run --project python --locked pytest python/tests

test-dart:
    cd packages/sf_im_client && dart test

test-flutter:
    cd packages/sf_im_flutter && flutter test --no-pub

test-scala:
    cd jvm && sbt test

test: test-python test-dart test-flutter test-scala

check: check-docs lint test

build-scala:
    cd jvm && sbt compile package

build-python:
    uv build --project python --no-sources --out-dir dist/python

# SDK 包没有 App 二进制；生成保留本地依赖的源码归档。
build-dart:
    python3 tools/package_dart.py sf_im_client

build-flutter:
    python3 tools/package_dart.py sf_im_flutter

build: build-scala build-python build-dart build-flutter

package: build

run-server:
    cd jvm && sbt 'server/run'

# 发布前置条件未配置；明确失败，避免误上传。
publish-scala:
    @python3 tools/publish_guard.py scala

publish-python:
    @python3 tools/publish_guard.py python

publish-dart:
    @python3 tools/publish_guard.py dart
