"""批量版本修改必须可预览、保留历史，并在原生工具失败时恢复元数据。"""

import shutil
import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))

from set_version import set_version  # noqa: E402
from versioning import MODULES, check_versions, index_content, release_versions, validate_version  # noqa: E402


@pytest.fixture
def repository(tmp_path):
    for relative in [
        "VERSION",
        "python-sdk/pyproject.toml",
        "python-sdk/uv.lock",
        "jvm/build.sbt",
        "protocol/openapi.yaml",
        "dart-sdk/pubspec.yaml",
        "dart-sdk/pubspec.lock",
        "flutter-sdk/pubspec.yaml",
        "flutter-sdk/pubspec.lock",
    ]:
        destination = tmp_path / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / relative, destination)
    shutil.copytree(ROOT / "changelog", tmp_path / "changelog")
    return tmp_path


def snapshot(root):
    return {p.relative_to(root): p.read_bytes() for p in root.rglob("*") if p.is_file()}


@pytest.mark.parametrize("version", ["v0.2.0", "01.2.0", "0.2", "0.2.0-rc.1", "../0.2.0"])
def test_reject_invalid_versions(version):
    with pytest.raises(ValueError):
        validate_version(version)


def next_version(root):
    major, minor, patch = map(int, (root / "VERSION").read_text().strip().split("."))
    return f"{major}.{minor}.{patch + 1}"


def test_dry_run_has_no_side_effects(repository):
    before = snapshot(repository)
    with patch("set_version.subprocess.run") as run:
        set_version(repository, next_version(repository), dry_run=True)
    run.assert_not_called()
    assert snapshot(repository) == before


def test_native_failure_restores_files_and_removes_new_logs(repository):
    before = snapshot(repository)
    with (
        patch("set_version.shutil.which", return_value="tool"),
        patch(
            "set_version.subprocess.run",
            side_effect=subprocess.CalledProcessError(1, "uv"),
        ),
        pytest.raises(subprocess.CalledProcessError),
    ):
        set_version(repository, next_version(repository))
    assert snapshot(repository) == before
    check_versions(repository)


@pytest.mark.parametrize("existing_release", [False, True])
def test_upgrade_updates_locks_and_preserves_existing_history(repository, existing_release):
    before = snapshot(repository / "changelog")
    old_version = (repository / "VERSION").read_text().strip()
    new_version = next_version(repository)
    custom_log = repository / "changelog" / f"{new_version}.md"
    if existing_release:
        custom_log.write_text(f"# {new_version}\n\n- 手工填写的真实变更，必须保留。\n")
    expected = custom_log.read_bytes() if existing_release else None

    def native_lock_update(command, *, cwd, check):
        assert check
        if command[0] == "uv":
            p = repository / "python-sdk/uv.lock"
            text = p.read_text().replace(
                f'name = "polyglot-im-python-sdk"\nversion = "{old_version}"',
                f'name = "polyglot-im-python-sdk"\nversion = "{new_version}"',
            )
            p.write_text(text)
        if command[0] == "flutter":
            p = repository / "flutter-sdk/pubspec.lock"
            p.write_text(
                p.read_text().replace(f'version: "{old_version}"', f'version: "{new_version}"')
            )

    with (
        patch("set_version.shutil.which", return_value="tool"),
        patch("set_version.subprocess.run", side_effect=native_lock_update),
    ):
        set_version(repository, new_version)
    check_versions(repository)
    if existing_release:
        assert custom_log.read_bytes() == expected
    else:
        for module in MODULES:
            assert (
                f"## {module if module != 'polyglot-im' else '项目与构建'}"
                in custom_log.read_text()
            )
    for name, content in before.items():
        if name != Path("README.md"):
            assert (repository / "changelog" / name).read_bytes() == content
    assert sorted(p.name for p in (repository / "changelog").iterdir()) == sorted(
        ["README.md", f"{old_version}.md", f"{new_version}.md"]
    )
    index = (repository / "changelog/README.md").read_text()
    assert f"]({old_version}.md)" in index and f"]({new_version}.md)" in index


@pytest.mark.parametrize("layout", ["missing", "nested"])
def test_check_rejects_missing_or_per_module_release_files(repository, layout):
    version = (repository / "VERSION").read_text().strip()
    if layout == "missing":
        (repository / "changelog" / f"{version}.md").unlink()
    else:
        nested = repository / "changelog/dart-sdk"
        nested.mkdir()
        (nested / f"{version}.md").write_text("legacy per-module release")
    with pytest.raises(ValueError, match="变更记录"):
        check_versions(repository)


def test_index_keeps_all_versions_in_numeric_order(repository):
    for version in ["0.2.0", "0.10.0", "1.0.0"]:
        (repository / "changelog" / f"{version}.md").write_text(f"# {version}\n")
    text = index_content("0.1.0", release_versions(repository))
    assert text.index("](1.0.0.md)") < text.index("](0.10.0.md)") < text.index("](0.2.0.md)")
    assert "](0.1.0.md)" in text
