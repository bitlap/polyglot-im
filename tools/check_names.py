"""校验 Cache/Outbox 命名、Server 设置类型与集中错误定义。"""

import re
from pathlib import Path

from check_layers import ROOT, SOURCES


def violations(source: Path, base: Path) -> list[str]:
    """检查单个源文件，不限制公共入口中的历史类型别名。"""
    relative = source.relative_to(base)
    errors = []
    if source.stem.lower().endswith("store"):
        errors.append(f"{relative}: 实现文件不能使用 Store 后缀")
    if "storage" in relative.parts:
        errors.append(f"{relative}: 缓存进入 cache，业务 Outbox 进入 store")
    if base.name == "server" and source.suffix == ".scala":
        text = source.read_text()
        if relative.parts[0] == "config":
            for name in re.findall(r"\bclass\s+(\w+)", text):
                if not name.endswith("Settings") or source.stem != name:
                    errors.append(
                        f"{relative}: 配置类型 {name} 须以 Settings 结尾并与文件同名"
                    )
        if relative != Path("domain/ApiError.scala") and re.search(
            r'\bApiError\(\s*[^,()]+,\s*(?:s?"|[^,()]+,\s*s?")', text
        ):
            errors.append(
                f"{relative}: 错误码和提示须引用 domain/ApiError.scala 中的定义"
            )
    if not {"adapters", "infrastructure"}.intersection(relative.parts):
        return errors
    text = source.read_text()
    classes = re.findall(r"\bclass\s+(\w+)", text)
    for name in classes:
        if name.endswith("Store"):
            errors.append(f"{relative}: 实现类 {name} 不能使用 Store 后缀")
        if name.endswith(("Cache", "Outbox")):
            folder = "cache" if name.endswith("Cache") else "store"
            filename = (
                name
                if source.suffix == ".scala"
                else re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()
            )
            if source.stem != filename or relative.parent.name != folder:
                errors.append(
                    f"{relative}: {name} 应位于 {folder}/{filename}{source.suffix}"
                )
    return errors


def check(root: Path = ROOT) -> None:
    """遍历六个模块；只检查手写源代码，历史测量记录不参与重命名。"""
    errors = []
    for module, directory in SOURCES.items():
        base = root / module / directory
        for source in base.rglob("*"):
            if source.suffix in {".py", ".dart", ".scala", ".js"}:
                errors.extend(
                    f"{module}: {error}" for error in violations(source, base)
                )
                if source.suffix == ".scala":
                    prefix = (
                        "polyglot.im.server"
                        if module == "scala-server"
                        else "polyglot.im"
                    )
                    expected = ".".join(
                        (prefix, *source.relative_to(base).parent.parts)
                    )
                    declared = re.search(
                        r"^package ([\w.]+)$", source.read_text(), re.MULTILINE
                    )
                    if declared is None or declared.group(1) != expected:
                        errors.append(
                            f"{module}: {source.name} 的 package 应为 {expected}"
                        )
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        "Checked Cache/Outbox names, server settings and error definitions across 6 modules"
    )


if __name__ == "__main__":
    check()
