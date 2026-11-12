"""校验中文文档内部链接、协议样例、YAML 和当前 OpenAPI。"""

import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote

import yaml
from check_diagrams import validate as validate_diagram
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate
from versioning import check_versions

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
    check_versions(ROOT)
    errors = []
    images = {}
    image_hashes = {}
    documents = [
        p
        for p in ROOT.rglob("*.md")
        if not IGNORED.intersection(p.relative_to(ROOT).parts)
    ]
    for document in documents:
        if document.name.upper() == "CHANGELOG.MD":
            errors.append(f"变更记录必须位于根目录 changelog/<版本号>.md：{document}")
        text = document.read_text()
        if "```mermaid" in text:
            errors.append(f"图示仅维护 SVG，不内嵌 Mermaid：{document}")
        content = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
        content = re.sub(r"`[^`]*`", "", content)
        for target in re.findall(r"!\[[^\]]*\]\(([^)]+)\)", content):
            asset = (
                target
                if target.startswith(("https://", "http://"))
                else (document.parent / unquote(target.split("#", 1)[0])).resolve()
            )
            if asset in images:
                errors.append(
                    f"同一图片只展示一次：{images[asset]} 与 {document}: {target}"
                )
            images[asset] = document
            if isinstance(asset, Path) and asset.is_file():
                digest = hashlib.sha256(asset.read_bytes()).hexdigest()
                if digest in image_hashes and image_hashes[digest] != asset:
                    errors.append(
                        f"同一图片不得复制后重复展示：{image_hashes[digest]} 与 {asset}"
                    )
                image_hashes[digest] = asset
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", content):
            if target.startswith(("https://", "http://", "mailto:", "#")):
                continue
            path = unquote(target.split("#", 1)[0])
            if path and not (document.parent / path).exists():
                errors.append(f"{document.relative_to(ROOT)}: broken link {target}")
    diagram_dir = ROOT / "docs/diagrams"
    for source in diagram_dir.glob("*.mmd"):
        errors.append(f"图示仅维护 SVG，不能保留源文件：{source}")
    for image in sorted(diagram_dir.glob("*.svg")):
        try:
            validate_diagram(ET.parse(image).getroot())
        except (ValueError, ET.ParseError) as error:
            errors.append(f"{image}: {error}")
        if image.resolve() not in images:
            errors.append(f"独立图未被文档展示：{image}")
    for document in ROOT.rglob("*.yaml"):
        if not IGNORED.intersection(document.relative_to(ROOT).parts):
            yaml.safe_load(document.read_text())
    yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text())
    schema = json.loads((ROOT / "protocol/message.schema.json").read_text())
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    fixture = json.loads((ROOT / "protocol/fixtures/message.json").read_text())
    validator.validate(fixture)
    validator.validate(
        json.loads((ROOT / "protocol/fixtures/direct-message.json").read_text())
    )
    for bad_seq in ["0", "-1", "01", 42]:
        if validator.is_valid({**fixture, "conversation_seq": bad_seq}):
            errors.append(f"Schema accepted invalid sequence: {bad_seq!r}")
    identity_schema = json.loads(
        (ROOT / "protocol/introspection.schema.json").read_text()
    )
    Draft202012Validator.check_schema(identity_schema)
    Draft202012Validator(identity_schema).validate(
        json.loads((ROOT / "protocol/fixtures/introspection.json").read_text())
    )
    http_schema = json.loads((ROOT / "protocol/http-response.schema.json").read_text())
    Draft202012Validator.check_schema(http_schema)
    http_validator = Draft202012Validator(http_schema)
    for name in ("http-success", "http-error"):
        http_validator.validate(
            json.loads((ROOT / f"protocol/fixtures/{name}.json").read_text())
        )
    notification_schema = json.loads(
        (ROOT / "protocol/notification.schema.json").read_text()
    )
    Draft202012Validator.check_schema(notification_schema)
    notification_validator = Draft202012Validator(notification_schema)
    notification = json.loads(
        (ROOT / "protocol/fixtures/notification.json").read_text()
    )
    notification_validator.validate(notification)
    review = json.loads((ROOT / "protocol/fixtures/review-message.json").read_text())
    validator.validate(review)
    notification_validator.validate(
        {
            "recipient_id": "author",
            "client_message_id": "review-contract",
            "type": review["type"],
            "payload": review["payload"],
        }
    )
    invalid_notification = {**notification, "payload": {"text": "missing task_id"}}
    if notification_validator.is_valid(invalid_notification):
        errors.append("Notification Schema accepted missing task_id")
    validate(yaml.safe_load((ROOT / "protocol/openapi.yaml").read_text()))
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"Checked {len(documents)} Markdown files, local links, YAML, message Schema and OpenAPI"
    )


if __name__ == "__main__":
    check()
