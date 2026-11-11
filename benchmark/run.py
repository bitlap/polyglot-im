"""实际 HTTP/WS + 临时 PostgreSQL 的自包含并发基准。"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import platform
import signal
import subprocess
import sys
import tempfile
import time
import uuid
from collections import Counter
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

import httpx
from websockets.asyncio.client import connect

from .metrics import summarize

ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = ("write_many_users", "write_hot_user", "history", "sync_empty")


def positive(value: str) -> int:
    result = int(value)
    if result <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return result


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--requests", type=positive, default=1000)
    parser.add_argument("--warmup", type=positive, default=100)
    parser.add_argument("--repeats", type=positive, default=3)
    parser.add_argument(
        "--concurrency", type=positive, nargs="+", default=[1, 8, 32, 128]
    )
    parser.add_argument("--ws-messages", type=positive, default=32)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "benchmark/results/latest.json"
    )
    parser.add_argument("--instances", type=positive, default=1)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args(argv)
    if args.smoke:
        args.requests, args.warmup, args.repeats = 16, 2, 1
        args.concurrency, args.ws_messages = [1, 4], 4
    if args.instances > 8:
        parser.error("instances must be <= 8")
    if max(args.concurrency) > 256 or max(args.concurrency) > args.requests:
        parser.error(
            "concurrency must be <= 256 and requests >= every concurrency level"
        )
    return args


@contextmanager
def server(instances=1):
    with tempfile.TemporaryDirectory(prefix="polyglot-im-benchmark-") as directory:
        path = Path(directory)
        env = {
            **os.environ,
            "POLYGLOT_IM_BENCH_DIRECTORY": str(path),
            "POLYGLOT_IM_BENCH_INSTANCES": str(instances),
        }
        with (path / "server.log").open("w+") as log:
            process = subprocess.Popen(
                ["sbt", "update", "run"],
                cwd=ROOT / "benchmark",
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + 240
                config = path / "config.json"
                while not config.exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        log.seek(0)
                        raise RuntimeError(
                            "benchmark startup failed:\n" + log.read()[-8000:]
                        )
                    time.sleep(0.2)
                yield json.loads(config.read_text())
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()


def notification(user: str, key: str) -> dict:
    return {
        "recipient_id": user,
        "client_message_id": key,
        "type": "system.notice",
        "payload": {"title": "并发测试", "text": "x" * 128, "benchmark_id": key},
    }


def auth(token: str) -> dict:
    return {"Authorization": "Bearer " + token}


def endpoint(config, index):
    urls = config.get("base_urls", [config["base_url"]])
    return urls[index % len(urls)]


async def batch(http, config, scenario, concurrency, count, cursors):
    """恰好 concurrency 个闭环 worker；池等待、网络和响应读取均计入延迟。"""
    samples, errors = [], Counter()
    index = 0
    run_id = str(uuid.uuid4())

    async def worker():
        nonlocal index
        while index < count:
            current = index
            index += 1
            user_index = current % len(config["users"])
            user = config["users"][user_index]
            started = time.perf_counter_ns()
            try:
                if scenario.startswith("write_"):
                    recipient = (
                        "bench-0" if scenario == "write_hot_user" else user["id"]
                    )
                    response = await http.post(
                        endpoint(config, current) + "/api/v1/im/notifications",
                        headers=auth(config["service_token"]),
                        json=notification(recipient, f"{run_id}-{current}"),
                    )
                    expected = 201
                else:
                    route = (
                        "/api/v1/im/messages?limit=20"
                        if scenario == "history"
                        else "/api/v1/im/sync"
                    )
                    params = (
                        {"cursor": cursors[user_index]}
                        if scenario == "sync_empty"
                        else None
                    )
                    response = await http.get(
                        endpoint(config, current) + route,
                        headers=auth(user["token"]),
                        params=params,
                    )
                    expected = 200
                if response.status_code != expected:
                    errors[f"HTTP_{response.status_code}"] += 1
                    continue
                envelope = response.json()
                data = envelope.get("data") if isinstance(envelope, dict) else None
                valid = isinstance(data, dict) and envelope.get("code") == 200
                if scenario.startswith("write_"):
                    valid = valid and bool(data.get("message_id"))
                elif scenario == "history":
                    valid = valid and bool(data.get("items"))
                else:
                    valid = valid and data.get("events") == []
                if not valid:
                    errors["INVALID_RESPONSE"] += 1
                    continue
                samples.append((time.perf_counter_ns() - started) / 1e6)
            except (httpx.HTTPError, ValueError):
                errors["CLIENT_ERROR"] += 1

    cpu_started = time.process_time()
    started = time.perf_counter()
    await asyncio.gather(*(worker() for _ in range(min(concurrency, count))))
    return {
        **summarize(samples, count, time.perf_counter() - started),
        "errors": dict(errors),
        "latencies_ms": samples,
        "client_cpu_seconds": time.process_time() - cpu_started,
    }


async def ws_trial(http, config, concurrency, messages):
    """每连接独立账户；多实例时 HTTP 写入与 WS 固定连接不同实例。"""
    run_id = str(uuid.uuid4())
    starts, latencies = {}, []
    ready = asyncio.Event()
    connected = 0
    errors = Counter()

    async def connection(index):
        nonlocal connected
        user = config["users"][index]
        session = await http.post(
            endpoint(config, index) + "/api/v1/im/session",
            headers=auth(user["token"]),
            json={"device_id": f"benchmark-{index}"},
        )
        session.raise_for_status()
        state = await http.get(
            endpoint(config, index) + "/api/v1/im/state", headers=auth(user["token"])
        )
        state.raise_for_status()
        url = httpx.URL(
            endpoint(config, index + 1).replace("http://", "ws://") + "/api/v1/im/ws",
            params={
                "ticket": session.json()["data"]["ticket"],
                "cursor": state.json()["data"]["cursor"],
            },
        )
        async with connect(str(url), max_size=2**20, max_queue=4) as websocket:
            connected += 1
            if connected == concurrency:
                ready.set()
            await ready.wait()
            complete = asyncio.Event()
            observed = set()

            async def receive():
                async for raw in websocket:
                    page = json.loads(raw)["data"]
                    for event in page["events"]:
                        key = (
                            event.get("data", {}).get("payload", {}).get("benchmark_id")
                        )
                        if key in starts and key not in observed:
                            observed.add(key)
                            latencies.append(
                                (time.perf_counter_ns() - starts[key]) / 1e6
                            )
                    await websocket.send(json.dumps({"cursor": page["cursor"]}))
                    if len(observed) == messages:
                        complete.set()
                        return

            receiver = asyncio.create_task(receive())
            try:
                for number in range(messages):
                    key = f"{run_id}-{index}-{number}"
                    starts[key] = time.perf_counter_ns()
                    response = await http.post(
                        endpoint(config, index) + "/api/v1/im/notifications",
                        headers=auth(config["service_token"]),
                        json=notification(user["id"], key),
                    )
                    if response.status_code != 201:
                        errors[f"HTTP_{response.status_code}"] += 1
                        raise RuntimeError("WebSocket benchmark send failed")
                await asyncio.wait_for(complete.wait(), timeout=30)
                await receiver
            finally:
                receiver.cancel()
                await asyncio.gather(receiver, return_exceptions=True)

    started = time.perf_counter()
    await asyncio.wait_for(
        asyncio.gather(*(connection(i) for i in range(concurrency))), timeout=90
    )
    return {
        **summarize(latencies, concurrency * messages, time.perf_counter() - started),
        "errors": dict(errors),
        "latencies_ms": latencies,
    }


async def measure(config, args):
    rows = []
    limits = httpx.Limits(
        max_connections=max(args.concurrency),
        max_keepalive_connections=max(args.concurrency),
    )
    async with httpx.AsyncClient(
        base_url=config["base_url"], limits=limits, timeout=30, trust_env=False
    ) as http:
        # 所有账户各建 20 条历史，避免把空列表误当分页读取容量。
        async def seed(user):
            for i in range(20):
                response = await http.post(
                    "/api/v1/im/notifications",
                    headers=auth(config["service_token"]),
                    json=notification(user["id"], f"seed-{user['id']}-{i}"),
                )
                response.raise_for_status()

        await asyncio.gather(*(seed(user) for user in config["users"]))
        for repeat in range(args.repeats):
            for scenario in SCENARIOS:
                cursors = []
                if scenario == "sync_empty":
                    for user in config["users"]:
                        response = await http.get(
                            "/api/v1/im/state", headers=auth(user["token"])
                        )
                        response.raise_for_status()
                        cursors.append(response.json()["data"]["cursor"])
                for concurrency in args.concurrency:
                    warm = await batch(
                        http, config, scenario, concurrency, args.warmup, cursors
                    )
                    if warm["failed"]:
                        raise RuntimeError(f"warmup failed: {warm['errors']}")
                    row = await batch(
                        http, config, scenario, concurrency, args.requests, cursors
                    )
                    rows.append(
                        {
                            "scenario": scenario,
                            "concurrency": concurrency,
                            "repeat": repeat + 1,
                            **row,
                        }
                    )
                    print(
                        f"{scenario} c={concurrency} trial={repeat + 1}: {row['successful_rps']:.1f} req/s, "
                        f"p99={row['p99_ms']} ms, errors={row['failed']}",
                        flush=True,
                    )
            for concurrency in args.concurrency:
                await ws_trial(
                    http, config, concurrency, 1
                )  # 连接/协议预热，不计入结果
                row = await ws_trial(http, config, concurrency, args.ws_messages)
                rows.append(
                    {
                        "scenario": "websocket_delivery",
                        "concurrency": concurrency,
                        "repeat": repeat + 1,
                        **row,
                    }
                )
                print(
                    f"websocket_delivery connections={concurrency}: p99={row['p99_ms']:.2f} ms",
                    flush=True,
                )
    return rows


def main(argv=None):
    args = parse_args(argv)
    revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
    ).strip()
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT))
    metadata = {
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "git_revision": revision,
        "working_tree_dirty": dirty,
        "version": (ROOT / "VERSION").read_text().strip(),
        "os": platform.platform(),
        "machine": platform.machine(),
        "python": platform.python_version(),
        "logical_cpus": os.cpu_count(),
        "load_generator": f"HTTPX {httpx.__version__}, asyncio closed-loop, no retry",
        "requests_per_trial": args.requests,
        "warmup_requests": args.warmup,
        "repeats": args.repeats,
        "concurrency": args.concurrency,
        "ws_messages_per_connection": args.ws_messages,
        "smoke": args.smoke,
    }
    if sys.platform == "darwin":
        metadata["cpu_model"] = subprocess.check_output(
            ["/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"], text=True
        ).strip()
        metadata["memory_bytes"] = int(
            subprocess.check_output(["/usr/sbin/sysctl", "-n", "hw.memsize"], text=True)
        )
    elif sys.platform.startswith("linux"):
        metadata["cpu_model"] = next(
            (
                line.split(":", 1)[1].strip()
                for line in Path("/proc/cpuinfo").read_text().splitlines()
                if line.startswith("model name")
            ),
            platform.processor(),
        )
        metadata["memory_bytes"] = (
            int(Path("/proc/meminfo").read_text().split("MemTotal:", 1)[1].split()[0])
            * 1024
        )
    with server(args.instances) as config:
        if args.smoke:
            config["users"] = config["users"][: max(args.concurrency)]
        metadata["active_users"] = len(config["users"])
        metadata["server"] = config["server"]
        if (
            config["server"]["postgres"]["fsync"] != "on"
            or config["server"]["postgres"]["synchronous_commit"] != "on"
        ):
            raise RuntimeError("durability settings must be on for this baseline")
        rows = asyncio.run(measure(config, args))
    output = {"metadata": metadata, "trials": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n")
    print(f"Result written: {args.output}")
    if any(row["failed"] for row in rows):
        raise SystemExit(
            "benchmark completed with request failures; inspect raw result"
        )


if __name__ == "__main__":
    main()
