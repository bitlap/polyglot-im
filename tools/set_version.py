"""批量设置项目版本并刷新锁文件；不提交、不打 tag、不发布。"""

import argparse
import json
import re
import shutil
import subprocess
from pathlib import Path

from versioning import (
    DART_PACKAGES,
    MODULES,
    check_versions,
    current_version,
    index_content,
    release_template,
    release_versions,
    validate_version,
)

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, pattern: str, replacement: str) -> str:
    result, count = re.subn(pattern, replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise ValueError(f"预期唯一版本字段，实际找到 {count} 个：{pattern}")
    return result


def planned_changes(root: Path, version: str) -> dict[Path, str]:
    validate_version(version)
    changes = {root / "VERSION": f"{version}\n"}
    p = root / "uniapp-client/package.json"
    package = json.loads(p.read_text())
    package["version"] = version
    changes[p] = json.dumps(package, ensure_ascii=False, indent=2) + "\n"
    p = root / "python-client/pyproject.toml"
    changes[p] = replace_once(
        p.read_text(), r'^version = "[^"]+"$', f'version = "{version}"'
    )
    for name in DART_PACKAGES:
        p = root / MODULES[name] / "pubspec.yaml"
        changes[p] = replace_once(
            p.read_text(), r"^version: .+$", f"version: {version}"
        )
    p = root / "flutter-client/ios/polyglot_im_flutter.podspec"
    if p.exists():
        changes[p] = replace_once(
            p.read_text(), r"^  s.version = '[^']+'$", f"  s.version = '{version}'"
        )
    for module in ("dart-client", "flutter-client"):
        p = root / "examples" / module / "pubspec.yaml"
        if p.exists():
            changes[p] = replace_once(
                p.read_text(), r"^version: .+$", f"version: {version}"
            )
    p = root / "protocol/openapi.yaml"
    changes[p] = replace_once(
        p.read_text(), r"^  version: .+$", f"  version: {version}"
    )
    changes[root / "changelog/README.md"] = index_content(
        version, release_versions(root)
    )
    p = root / "changelog" / f"{version}.md"
    if not p.exists():
        changes[p] = release_template(version)
    return changes


def set_version(root: Path, version: str, *, dry_run: bool = False) -> None:
    changes = planned_changes(root, version)
    commands = [
        (["uv", "lock", "--project", "python-client"], root),
        (["dart", "pub", "get"], root / "dart-client"),
        (["flutter", "pub", "get"], root / "flutter-client"),
    ]
    for module, tool in (("dart-client", "dart"), ("flutter-client", "flutter")):
        if (root / "examples" / module / "pubspec.yaml").exists():
            commands.append(([tool, "pub", "get"], root / "examples" / module))
    if dry_run:
        for p, text in changes.items():
            if not p.exists() or p.read_text() != text:
                print(f"更新 {p.relative_to(root)}")
        print("随后刷新 uv.lock 和 Dart/Flutter 锁文件；不写入、不执行外部命令。")
        return
    for command, _ in commands:
        if shutil.which(command[0]) is None:
            raise ValueError(f"缺少工具 {command[0]}，尚未修改文件")
    locks = [
        root / "python-client/uv.lock",
        root / "dart-client/pubspec.lock",
        root / "flutter-client/pubspec.lock",
    ]
    for module in ("dart-client", "flutter-client"):
        if (root / "examples" / module / "pubspec.yaml").exists():
            locks.append(root / "examples" / module / "pubspec.lock")
    snapshots = {p: p.read_bytes() if p.exists() else None for p in [*changes, *locks]}
    try:
        for p, content in changes.items():
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
        for command, cwd in commands:
            subprocess.run(command, cwd=cwd, check=True)
        check_versions(root)
    except (OSError, ValueError, subprocess.CalledProcessError, KeyboardInterrupt):
        for p, content in snapshots.items():
            if content is None:
                p.unlink(missing_ok=True)
            else:
                p.write_bytes(content)
        raise
    print(f"全部子项目版本已统一为 {version}，锁文件已刷新；既有变更记录保持原样。")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", nargs="?")
    parser.add_argument(
        "--check", action="store_true", help="只检查版本、锁文件和变更记录"
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="预览变更，不写入或调用工具"
    )
    args = parser.parse_args()
    if args.check:
        if args.version or args.dry_run:
            parser.error("--check 不能与版本参数或 --dry-run 同时使用")
        check_versions(ROOT)
        print(f"全部子项目版本与锁文件一致：{current_version(ROOT)}")
    else:
        if not args.version:
            parser.error("请提供版本号，例如 0.1.0")
        set_version(ROOT, args.version, dry_run=args.dry_run)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
