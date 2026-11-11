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
from versioning import check_versions, validate_version  # noqa: E402


@pytest.fixture
def repository(tmp_path):
    for relative in [
        "VERSION",
        "python/pyproject.toml",
        "python/uv.lock",
        "jvm/build.sbt",
        "protocol/openapi.yaml",
        "packages/polyglot_im_client/pubspec.yaml",
        "packages/polyglot_im_client/pubspec.lock",
        "packages/polyglot_im_flutter/pubspec.yaml",
        "packages/polyglot_im_flutter/pubspec.lock",
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


def test_upgrade_updates_locks_and_preserves_existing_history(repository):
    before = snapshot(repository / "changelog")
    old_version = (repository / "VERSION").read_text().strip()
    new_version = next_version(repository)
    custom_log = repository / "changelog/polyglot_im_client" / f"{new_version}.md"
    custom_log.write_text(f"# {new_version}\n\n- 手工填写的真实变更，必须保留。\n")

    def native_lock_update(command, *, cwd, check):
        assert check
        if command[0] == "uv":
            p = repository / "python/uv.lock"
            text = p.read_text().replace(
                f'name = "polyglot-im-python"\nversion = "{old_version}"',
                f'name = "polyglot-im-python"\nversion = "{new_version}"',
            )
            p.write_text(text)
        if command[0] == "flutter":
            p = repository / "packages/polyglot_im_flutter/pubspec.lock"
            p.write_text(
                p.read_text().replace(f'version: "{old_version}"', f'version: "{new_version}"')
            )

    with (
        patch("set_version.shutil.which", return_value="tool"),
        patch("set_version.subprocess.run", side_effect=native_lock_update),
    ):
        set_version(repository, new_version)
    check_versions(repository)
    assert "手工填写的真实变更" in custom_log.read_text()
    for name, content in before.items():
        if name != Path("README.md"):
            assert (repository / "changelog" / name).read_bytes() == content
    assert (repository / "changelog/polyglot-im-server" / f"{new_version}.md").is_file()
