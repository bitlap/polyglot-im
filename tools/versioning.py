"""统一版本与根目录变更记录布局，使用 Python 3.11+ 标准库。"""

import json
import re
import tomllib
from pathlib import Path

MODULES = {
    "polyglot-im": ".",
    "scala-client": "scala-client",
    "scala-server": "scala-server",
    "dart-client": "dart-client",
    "flutter-client": "flutter-client",
    "python-client": "python-client",
    "benchmark": "benchmark",
    "uniapp-client": "uniapp-client",
}
DART_PACKAGES = {
    "dart-client": "polyglot_im",
    "flutter-client": "polyglot_im_flutter",
}
VERSION_PATTERN = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")


def validate_version(version: str) -> str:
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError(
            "版本号必须为 X.Y.Z，例如 0.1.0；不接受前导 v、前导零或预发布后缀"
        )
    return version


def current_version(root: Path) -> str:
    return validate_version((root / "VERSION").read_text().strip())


def release_versions(root: Path) -> list[str]:
    directory = root / "changelog"
    versions = []
    for path in directory.glob("*.md"):
        if path.is_file() and path.name != "README.md":
            versions.append(validate_version(path.stem))
    return sorted(
        versions, key=lambda value: tuple(map(int, value.split("."))), reverse=True
    )


def index_content(version: str, versions: list[str]) -> str:
    ordered = sorted(
        {*versions, version},
        key=lambda value: tuple(map(int, value.split("."))),
        reverse=True,
    )
    rows = "\n".join(f"| [{item}]({item}.md) |" for item in ordered)
    return (
        "# 变更记录\n\n"
        "每个版本仅维护一个文件：`changelog/<版本号>.md`，"
        "文件内按模块汇总该版本的全部变化，不再按子项目建立目录。\n\n"
        "此索引由 `just set-version <版本号>` 更新，保留全部历史版本。\n\n"
        "| 版本记录 |\n| --- |\n"
        f"{rows}\n\n"
        "[返回 Polyglot IM 项目首页](../README.md)\n"
    )


def release_template(version: str) -> str:
    sections = "\n\n".join(
        f"## {'项目与构建' if module == 'polyglot-im' else module}\n\n- 待补充本版本变更。"
        for module in MODULES
    )
    return f"# Polyglot IM {version}\n\n{sections}\n"


def check_versions(root: Path) -> None:
    version = current_version(root)
    if (
        json.loads((root / "uniapp-client/package.json").read_text())["version"]
        != version
    ):
        raise ValueError("uni-app 版本与 VERSION 不一致")
    python = tomllib.loads((root / "python-client/pyproject.toml").read_text())
    if python["project"]["version"] != version:
        raise ValueError("Python 版本与 VERSION 不一致")
    for name, package_name in DART_PACKAGES.items():
        text = (root / MODULES[name] / "pubspec.yaml").read_text()
        if not re.search(rf"^name: {re.escape(package_name)}$", text, re.MULTILINE):
            raise ValueError(f"{name} 发布名称与模块注册表不一致")
        match = re.search(r"^version: (.+)$", text, re.MULTILINE)
        if not match or match[1] != version:
            raise ValueError(f"{name} 版本与 VERSION 不一致")
    podspec = root / "flutter-client/ios/polyglot_im_flutter.podspec"
    if podspec.exists() and f"s.version = '{version}'" not in podspec.read_text():
        raise ValueError("iOS podspec 版本与 VERSION 不一致")
    for module in ("dart-client", "flutter-client"):
        example = root / "examples" / module / "pubspec.yaml"
        if example.exists() and f"version: {version}\n" not in example.read_text():
            raise ValueError(f"{module} 示例版本与 VERSION 不一致")
    if (root / "build.sbt").exists():
        raise ValueError("Scala 构建必须位于各子项目目录，不在根目录聚合")
    for module in ("scala-client", "scala-server", "benchmark"):
        sbt = (root / module / "build.sbt").read_text()
        if not re.search(
            r'ThisBuild / version\s*:=\s*IO\.read\([\s\S]*? / "VERSION"\s*\)\.trim', sbt
        ):
            raise ValueError(f"{module} 构建必须从根目录 VERSION 读取版本")
        for filename in (
            "project/build.properties",
            "project/plugins.sbt",
            ".scalafmt.conf",
        ):
            if not (root / module / filename).is_file():
                raise ValueError(f"{module} 缺少独立构建配置 {filename}")
    api = (root / "protocol/openapi.yaml").read_text()
    if not re.search(rf"^  version: {re.escape(version)}$", api, re.MULTILINE):
        raise ValueError("OpenAPI 服务版本与 VERSION 不一致")
    lock = tomllib.loads((root / "python-client/uv.lock").read_text())
    package = next((p for p in lock["package"] if p["name"] == "polyglot-im"), None)
    if package is None:
        raise ValueError("Python 锁文件缺少当前项目")
    if package["version"] != version:
        raise ValueError("Python 锁文件版本不一致，请执行 just set-version")
    flutter_lock = (root / "flutter-client/pubspec.lock").read_text()
    match = re.search(
        r'^  polyglot_im:\n(?:(?!^  \S)[\s\S])*?^    version: "([^"]+)"',
        flutter_lock,
        re.MULTILINE,
    )
    if not match or match[1] != version:
        raise ValueError("Flutter 本地 Dart 依赖锁版本不一致，请执行 just set-version")
    changelog = root / "changelog"
    if not (changelog / f"{version}.md").is_file():
        raise ValueError(f"缺少 {version} 的统一变更记录")
    if any(
        path.is_file() and path.parent != changelog for path in changelog.rglob("*")
    ):
        raise ValueError("变更记录必须直接放在 changelog/<版本号>.md，不得按模块分目录")
    if (changelog / "README.md").read_text() != index_content(
        version, release_versions(root)
    ):
        raise ValueError("变更记录索引与版本文件不一致")
