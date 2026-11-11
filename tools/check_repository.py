"""校验中文文档内部链接、协议样例、YAML 和当前 OpenAPI。"""

import json
import re
from pathlib import Path
from urllib.parse import unquote

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[1]
IGNORED = {
    ".git",
    ".venv",
    ".dart_tool",
    "target",
    "build",
    "dist",
    "__pycache__",
    ".pytest_cache",
    ".ruff_cache",
}


def check() -> None:
    errors = []
    documents = [
        p
        for p in ROOT.rglob("*.md")
        if not IGNORED.intersection(p.relative_to(ROOT).parts)
    ]
    for document in documents:
        content = re.sub(r"```.*?```", "", document.read_text(), flags=re.DOTALL)
        content = re.sub(r"`[^`]*`", "", content)
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", content):
            if target.startswith(("https://", "http://", "mailto:", "#")):
                continue
            path = unquote(target.split("#", 1)[0])
            if path and not (document.parent / path).exists():
                errors.append(f"{document.relative_to(ROOT)}: broken link {target}")
    for document in ROOT.rglob("*.yaml"):
        if not IGNORED.intersection(document.relative_to(ROOT).parts):
            yaml.safe_load(document.read_text())
    yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text())
    schema = json.loads((ROOT / "protocol/message.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    fixture = json.loads((ROOT / "protocol/fixtures/message.json").read_text())
    validator.validate(fixture)
    for bad_seq in ["0", "-1", "01", 42]:
        if validator.is_valid({**fixture, "conversation_seq": bad_seq}):
            errors.append(f"Schema accepted invalid sequence: {bad_seq!r}")
    validate(yaml.safe_load((ROOT / "protocol/openapi.yaml").read_text()))
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"Checked {len(documents)} Markdown files, local links, YAML, message Schema and OpenAPI"
    )


if __name__ == "__main__":
    check()
