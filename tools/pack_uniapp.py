"""构建本地 npm 依赖包，从根目录注入许可正文，不向 registry 发布。"""

import shutil
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
output = root / "dist/uniapp-client"
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="polyglot-im-uniapp-") as directory:
    package = Path(directory)
    for name in ("package.json", "index.js", "README.md"):
        shutil.copyfile(root / "uniapp-client" / name, package / name)
    shutil.copytree(root / "uniapp-client/src", package / "src")
    for name in ("LICENSE", "NOTICE"):
        shutil.copyfile(root / name, package / name)
    subprocess.run(
        ["npm", "pack", "--ignore-scripts", "--pack-destination", str(output)],
        cwd=package,
        check=True,
    )
