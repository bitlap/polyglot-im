"""持续 WS 测量不能被报告为固定每连接消息数。"""

import json
from pathlib import Path

from benchmark import report


def test_websocket_report_distinguishes_duration_and_fixed_message_workloads():
    root = Path(__file__).resolve().parents[2]
    result = json.loads(
        (root / "benchmark/results/distributed-single-2026-11-11.json").read_text()
    )
    metadata = result["metadata"]
    metadata.update(duration_seconds=60, warmup_seconds=10)
    rendered = report.render(result, "result.json")
    assert "统一发送窗口 60 秒" in rendered
    assert f"每连接 {metadata['ws_messages_per_connection']} 条；" not in rendered
    assert "协议预热每连接 1 条" in rendered
    metadata["duration_seconds"] = 0
    rendered = report.render(result, "result.json")
    assert f"每连接 {metadata['ws_messages_per_connection']} 条；" in rendered
