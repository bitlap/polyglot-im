"""保持服务与用户不变，对照单/四进程 WS 发压，接入凭证只从私有文件读取。"""

import argparse
import asyncio
import hashlib
import json
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from benchmark.load_generator_probe import round_probe
from benchmark.run import ROOT, positive, seconds, write_result
from benchmark.runtime import validate_manifest


async def measure(config, options):
    """以 4/1、1/4 的交替顺序测量，中央采样数据库并逐轮保存结果。"""
    metadata = {
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "git_revision": (
            await asyncio.to_thread(
                subprocess.check_output,
                ["git", "rev-parse", "HEAD"],
                cwd=ROOT,
                text=True,
            )
        ).strip(),
        "working_tree_dirty": bool(
            await asyncio.to_thread(
                subprocess.check_output, ["git", "status", "--porcelain"], cwd=ROOT
            )
        ),
        "server": config["server"],
        "scenario": "websocket_delivery",
        "duration_seconds": options.duration,
        "total_concurrency": options.concurrency,
        "runner_sha256": {
            name: hashlib.sha256((ROOT / "benchmark" / name).read_bytes()).hexdigest()
            for name in ("run.py", "load_generator_probe.py", "ws_generator_probe.py")
        },
    }
    rows = []
    for repeat in range(options.repeats):
        for processes in (4, 1) if repeat % 2 == 0 else (1, 4):
            row = await round_probe(
                config,
                processes,
                options.concurrency,
                options.duration,
                "websocket_delivery",
            )
            row["repeat"] = repeat + 1
            rows.append(row)
            write_result(options.output, {"metadata": metadata, "trials": rows})
            print(
                f"processes={processes} attempted={row['attempted']} failed={row['failed']} p99_ms={row['p99_ms']} connection_errors={row['connection_errors']}",
                flush=True,
            )
    if any(
        row["failed"]
        or row["connection_errors"]
        or row["observation_errors"]
        or not row["diagnostics"]["complete"]
        or any(row["worker_exit_codes"].values())
        for row in rows
    ):
        raise SystemExit("WS probe failed; inspect saved results")


def main():
    """读取独立夹具接入文件，检查总并发后执行 WS 发压端对照。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=seconds, default=60)
    parser.add_argument("--concurrency", type=positive, default=128)
    parser.add_argument("--repeats", type=positive, default=2)
    options = parser.parse_args()
    config = validate_manifest(json.loads(options.config.read_text()))
    if options.concurrency % 4 or options.concurrency > len(config["users"]):
        parser.error(
            "concurrency must be divisible by four and not exceed configured users"
        )
    asyncio.run(measure(config, options))


if __name__ == "__main__":
    main()
