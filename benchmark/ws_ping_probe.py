"""在专用夹具中加速 Ping，与业务数据并发下行，检查 WS 帧完整性。"""

import argparse
import asyncio
import hashlib
import json
from datetime import UTC, datetime
from functools import partial
from pathlib import Path

import httpx

from benchmark import run
from benchmark.runtime import validate_manifest
from benchmark.telemetry import observe


async def measure(config, options):
    """缩短客户端 Ping 间隔，逐轮保存连接错误；这不是正常业务容量测试。"""
    metadata = {
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "server": config["server"],
        "server_build_sha256": hashlib.sha256(
            (run.ROOT / "scala-server/build.sbt").read_bytes()
        ).hexdigest(),
        "duration_seconds": options.duration,
        "concurrency": options.concurrency,
        "ping_interval_seconds": options.ping_interval,
        "ping_timeout_seconds": 20,
    }
    rows = []
    original_connect = run.connect
    run.connect = partial(
        original_connect, ping_interval=options.ping_interval, ping_timeout=20
    )
    try:
        async with httpx.AsyncClient(
            timeout=30,
            trust_env=False,
            limits=httpx.Limits(
                max_connections=options.concurrency,
                max_keepalive_connections=options.concurrency,
            ),
        ) as http:
            for repeat in range(options.repeats):
                row = await observe(
                    config,
                    lambda: run.ws_trial(
                        http, config, options.concurrency, 1, options.duration
                    ),
                    1,
                )
                row["repeat"] = repeat + 1
                rows.append(row)
                run.write_result(options.output, {"metadata": metadata, "trials": rows})
                print(
                    f"repeat={repeat + 1} attempted={row['attempted']} failed={row['failed']} connection_errors={row['connection_errors']}",
                    flush=True,
                )
    finally:
        run.connect = original_connect
    if any(
        row["failed"]
        or row["connection_errors"]
        or row["observation_errors"]
        or not row["diagnostics"]["complete"]
        for row in rows
    ):
        raise SystemExit("WS ping probe failed; inspect saved results")


def main():
    """只接受专用压测接入文件，显式指定输出并执行有界并发心跳诊断。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=run.seconds, default=30)
    parser.add_argument("--concurrency", type=run.positive, default=128)
    parser.add_argument("--repeats", type=run.positive, default=3)
    parser.add_argument("--ping-interval", type=run.seconds, default=0.05)
    options = parser.parse_args()
    config = validate_manifest(json.loads(options.config.read_text()))
    if options.concurrency > len(config["users"]):
        parser.error("concurrency must not exceed configured users")
    asyncio.run(measure(config, options))


if __name__ == "__main__":
    main()
