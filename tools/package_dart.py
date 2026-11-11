"""归档私有 Dart/Flutter SDK 源码；不是可直接上传 pub.dev 的发布包。"""

import re
import sys
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {"polyglot_im_client", "polyglot_im_flutter"}


def package(name: str) -> None:
    if name not in ALLOWED:
        raise SystemExit("Unknown SDK package")
    source = ROOT / "packages" / name
    metadata = (source / "pubspec.yaml").read_text()
    version = re.search(r"^version: ([0-9A-Za-z.+-]+)$", metadata, re.MULTILINE)
    if version is None:
        raise SystemExit("Missing package version")
    destination = ROOT / "dist" / "dart" / f"{name}-{version[1]}.tar.gz"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, "w:gz") as archive:
        for item in ["lib", "pubspec.yaml", "README.md", "CHANGELOG.md", "LICENSE"]:
            archive.add(source / item, arcname=f"{name}/{item}")
    print(f"Created {destination.relative_to(ROOT)} (private source bundle)")


if __name__ == "__main__":
    package(sys.argv[1])
