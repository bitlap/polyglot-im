"""检查核心层不反向依赖 API、适配器或具体网络/数据库驱动。"""

import ast
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ALLOWED = {
    "common": {"common"},
    "domain": {"domain", "common"},
    "config": {"config", "common", "domain"},
    "ports": {"ports", "domain", "common"},
    "service": {"service", "ports", "domain", "common", "config"},
    "presentation": {
        "presentation",
        "api",
        "service",
        "ports",
        "domain",
        "common",
        "config",
    },
}
PYTHON_DRIVERS = {"sqlite3", "psycopg", "psycopg_pool", "httpx", "websockets"}
SCALA_DRIVERS = (
    "java.sql",
    "javax.sql",
    "java.net.http",
    "java.nio.file.Files",
    "org.http4s",
    "org.postgresql",
    "com.zaxxer",
)
DART_DRIVERS = (
    "package:http/",
    "package:web_socket_channel/",
    "package:sqflite/",
    "package:sqflite_common/",
    "package:sqflite_common_ffi/",
    "package:sqlite3/",
    "package:flutter/services.dart",
)
SOURCES = {
    "python-client": "src/polyglot_im",
    "scala-client": "src/main/scala/polyglot/im",
    "scala-server": "src/main/scala/polyglot/im/server",
    "dart-client": "lib/src",
    "flutter-client": "lib/src",
    "uniapp-client": "src",
}


def violations(source: Path, base: Path) -> list[str]:
    relative = source.relative_to(base)
    layer = relative.parts[0]
    if layer not in ALLOWED or len(relative.parts) < 2:
        return []  # 兼容导出、组合根和具体适配器不属于受保护的内层。
    text = source.read_text()
    imports = []
    dependencies = []
    if source.suffix == ".py":
        package = ["polyglot_im", *relative.parts[:-1]]
        for node in ast.walk(ast.parse(text)):
            if isinstance(node, ast.Import):
                imports.extend(alias.name for alias in node.names)
            if isinstance(node, ast.ImportFrom):
                name = node.module or ""
                imports.append(name)
                resolved = (
                    package[: len(package) - node.level + 1] + name.split(".")
                    if node.level
                    else name.split(".")
                )
                if resolved[0] == "polyglot_im":
                    dependencies.append(
                        resolved[1] if len(resolved) > 1 else "public-facade"
                    )
        for name in imports:
            if name.split(".")[0] == "polyglot_im":
                dependencies.append(
                    name.split(".")[1] if "." in name else "public-facade"
                )
        drivers = [name for name in imports if name.split(".")[0] in PYTHON_DRIVERS]
    elif source.suffix == ".scala":
        # Also match fully qualified constructor references, which can bypass imports.
        prefix = "polyglot.im.server" if base.name == "server" else "polyglot.im"
        dependencies = re.findall(re.escape(prefix) + r"\.(\w+)", text)
        if re.search(r"import\s+" + re.escape(prefix) + r"\.(?:\{|\*)", text):
            dependencies.append("public-facade")
        drivers = [name for name in SCALA_DRIVERS if name in text]
    elif source.suffix == ".js":
        imports = re.findall(r"(?:import|export)\s+.*?from\s+['\"]([^'\"]+)['\"]", text)
        for name in imports:
            if name.startswith("."):
                target = (source.parent / name).resolve()
                if target.is_relative_to(base.resolve()):
                    dependencies.append(target.relative_to(base.resolve()).parts[0])
        drivers = re.findall(
            r"\buni\.(?:request|connectSocket|setStorageSync|getStorageSync)\b", text
        )
    else:
        imports = re.findall(r"(?:import|export)\s+'([^']+)'", text)
        for name in imports:
            if not name.startswith(("package:", "dart:")):
                target = (source.parent / name).resolve()
                if target.is_relative_to(base.resolve()):
                    dependencies.append(target.relative_to(base.resolve()).parts[0])
        drivers = [name for name in imports if name.startswith(DART_DRIVERS)]
    errors = [
        f"{relative}: {layer} 不允许依赖 {name}"
        for name in set(dependencies) - ALLOWED[layer]
    ]
    errors += [f"{relative}: 具体驱动 {name} 应放在适配器层" for name in drivers]
    return sorted(errors)


def check(root: Path = ROOT) -> None:
    errors = []
    count = 0
    for module, directory in SOURCES.items():
        base = root / module / directory
        for source in base.rglob("*"):
            if source.suffix in {".py", ".dart", ".scala", ".js"}:
                errors.extend(
                    f"{module}: {error}" for error in violations(source, base)
                )
                count += 1
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"Checked layer boundaries across {count} source files in {len(SOURCES)} modules"
    )


if __name__ == "__main__":
    check()
