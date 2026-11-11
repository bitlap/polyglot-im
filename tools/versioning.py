"""统一版本与根目录变更记录布局，使用 Python 3.11+ 标准库。"""

import re
import tomllib
from pathlib import Path

MODULES = {
    "polyglot-im": ".",
    "scala-sdk": "jvm/scala-sdk",
    "scala-server": "jvm/scala-server",
    "dart-sdk": "dart-sdk",
    "flutter-sdk": "flutter-sdk",
    "python-sdk": "python-sdk",
    "benchmark": "benchmark",
}
DART_PACKAGES = {
    "dart-sdk": "polyglot_im_dart_sdk",
    "flutter-sdk": "polyglot_im_flutter_sdk",
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


def index_content(version: str) -> str:
    rows = "\n".join(
        f"| {name} | `{path}` | [{version}]({name}/{version}.md) |"
        for name, path in MODULES.items()
    )
    return (
        "# 变更记录\n\n"
        "全部模块的变更记录集中于根目录 `changelog/<模块名>/<版本号>.md`。"
        "文件名只使用版本号；同一模块的历史版本保留，不覆盖。\n\n"
        "此索引由 `just set-version <版本号>` 更新。版本号不代表功能已完成或包已发布。\n\n"
        "| 模块 | 源码目录 | 当前版本记录 |\n| --- | --- | --- |\n"
        f"{rows}\n"
    )


def check_versions(root: Path) -> None:
    version = current_version(root)
    python = tomllib.loads((root / "python-sdk/pyproject.toml").read_text())
    if python["project"]["version"] != version:
        raise ValueError("Python 版本与 VERSION 不一致")
    for name, package_name in DART_PACKAGES.items():
        text = (root / MODULES[name] / "pubspec.yaml").read_text()
        if not re.search(rf"^name: {re.escape(package_name)}$", text, re.MULTILINE):
            raise ValueError(f"{name} 发布名称与模块注册表不一致")
        match = re.search(r"^version: (.+)$", text, re.MULTILINE)
        if not match or match[1] != version:
            raise ValueError(f"{name} 版本与 VERSION 不一致")
    podspec = root / "flutter-sdk/ios/polyglot_im_flutter_sdk.podspec"
    if podspec.exists() and f"s.version = '{version}'" not in podspec.read_text():
        raise ValueError("iOS podspec 版本与 VERSION 不一致")
    example = root / "examples/flutter_app/pubspec.yaml"
    if example.exists() and f"version: {version}\n" not in example.read_text():
        raise ValueError("Flutter 示例版本与 VERSION 不一致")
    sbt = (root / "jvm/build.sbt").read_text()
    if not re.search(
        r'ThisBuild / version\s*:=\s*IO\.read\([\s\S]*? / "VERSION"\s*\)\.trim', sbt
    ):
        raise ValueError("Scala 构建必须从根目录 VERSION 读取版本")
    api = (root / "protocol/openapi.yaml").read_text()
    if not re.search(rf"^  version: {re.escape(version)}$", api, re.MULTILINE):
        raise ValueError("OpenAPI 服务版本与 VERSION 不一致")
    lock = tomllib.loads((root / "python-sdk/uv.lock").read_text())
    package = next(
        (p for p in lock["package"] if p["name"] == "polyglot-im-python-sdk"), None
    )
    if package is None:
        raise ValueError("Python 锁文件缺少当前项目")
    if package["version"] != version:
        raise ValueError("Python 锁文件版本不一致，请执行 just set-version")
    flutter_lock = (root / "flutter-sdk/pubspec.lock").read_text()
    match = re.search(
        r'^  polyglot_im_dart_sdk:\n(?:(?!^  \S)[\s\S])*?^    version: "([^"]+)"',
        flutter_lock,
        re.MULTILINE,
    )
    if not match or match[1] != version:
        raise ValueError("Flutter 本地 Dart 依赖锁版本不一致，请执行 just set-version")
    for module in MODULES:
        if not (root / "changelog" / module / f"{version}.md").is_file():
            raise ValueError(f"缺少 {module} 的 {version} 变更记录")
    if (root / "changelog/README.md").read_text() != index_content(version):
        raise ValueError("变更记录索引与当前版本不一致")
