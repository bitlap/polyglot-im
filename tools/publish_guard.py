"""初始化阶段不允许误发布；接入真实发布工作流后再替换。"""

import sys

module = sys.argv[1] if len(sys.argv) > 1 else "unknown"
raise SystemExit(
    f"{module} 发布未启用：请先确认许可证、注册表归属、版本、凭证与产物消费验证。"
    "详见 docs/building.md；当前命令不会上传任何包。"
)
