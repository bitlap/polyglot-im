"""实际 HTTP/WS + 临时 PostgreSQL 的自包含并发基准。"""

from __future__ import annotations

import argparse
import asyncio
import gzip
import json
import os
import platform
import subprocess
import sys
import time
import uuid
from collections import Counter
from contextlib import nullcontext
from datetime import UTC, datetime
from pathlib import Path

import httpx
from websockets.asyncio.client import connect

from .metrics import summarize
from .runtime import server, validate_manifest
from .telemetry import observe

ROOT = Path(__file__).resolve().parents[1]
WRITE_SCENARIOS = (
    "write_many_users",
    "write_hot_user",
    "write_many_producers_many_users",
    "write_many_producers_hot_user",
)
SCENARIOS = (*WRITE_SCENARIOS, "history", "sync_empty")


def positive(value: str) -> int:
    """解析有界配置使用的正整数。"""
    result = int(value)
    if result <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return result


def seconds(value: str) -> float:
    """解析有限正数秒数，拒绝无穷和 NaN。"""
    import math

    result = float(value)
    if not math.isfinite(result) or result <= 0:
        raise argparse.ArgumentTypeError("must be finite and positive")
    return result


def parse_args(argv=None):
    """读取压测、资源总预算和独立发压接入选项；冒烟始终保持短时有界。"""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--requests", type=positive)
    mode.add_argument("--duration", type=seconds)
    parser.add_argument("--warmup", type=positive, default=100)
    parser.add_argument("--warmup-seconds", type=seconds, default=10)
    parser.add_argument("--repeats", type=positive, default=3)
    parser.add_argument(
        "--concurrency", type=positive, nargs="+", default=[1, 8, 32, 128]
    )
    parser.add_argument("--ws-messages", type=positive, default=32)
    parser.add_argument(
        "--output", type=Path, default=ROOT / "benchmark/results/latest.json"
    )
    parser.add_argument("--instances", type=positive, default=1)
    parser.add_argument("--db-connections", type=positive, default=8)
    parser.add_argument("--server-cpus", type=positive, default=4)
    parser.add_argument("--server-heap-mib", type=positive, default=2048)
    parser.add_argument("--users", type=positive, default=256)
    parser.add_argument("--producers", type=positive, default=16)
    parser.add_argument(
        "--scenarios",
        nargs="+",
        choices=(*SCENARIOS, "websocket_delivery"),
        default=[*SCENARIOS, "websocket_delivery"],
    )
    fixture = parser.add_mutually_exclusive_group()
    fixture.add_argument("--config", type=Path, help="独立发压机读取的专用夹具接入文件")
    fixture.add_argument(
        "--serve-only",
        type=Path,
        help="仅启动夹具；将目录内 config.json 安全复制到发压机",
    )
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--advertise-host", default="127.0.0.1")
    parser.add_argument("--sample-interval", type=seconds, default=1.0)
    parser.add_argument("--smoke", action="store_true")
    args = parser.parse_args(argv)
    args.duration = 0 if args.requests else (args.duration or 60)
    if args.requests:
        args.warmup_seconds = 0
    if args.smoke:
        args.requests, args.duration, args.warmup, args.warmup_seconds, args.repeats = (
            16,
            0,
            2,
            0,
            1,
        )
        args.concurrency, args.ws_messages = [1, 4], 4
        args.users, args.producers = 4, 4
    if args.instances > 8 or args.users > 256 or args.producers > 256:
        parser.error("instances <= 8; users/producers <= 256 required")
    if (
        args.db_connections < args.instances
        or args.server_cpus < args.instances
        or args.server_heap_mib < 128 * args.instances
    ):
        parser.error(
            "total budgets must provide >=1 connection/CPU and >=128 MiB per instance"
        )
    if max(args.concurrency) > min(256, args.users) or (
        args.requests is not None and max(args.concurrency) > args.requests
    ):
        parser.error("concurrency <= users <= 256 and requests >= concurrency required")
    if (
        not args.config
        and any(name.startswith("write_many_producers") for name in args.scenarios)
        and args.producers < 2
    ):
        parser.error("multi-producer scenarios require --producers >= 2")
    if (
        not args.config
        and any(name.endswith("many_users") for name in args.scenarios)
        and args.users < 2
    ):
        parser.error("multi-recipient scenarios require --users >= 2")
    if args.sample_interval < 0.2:
        parser.error("sample interval must be >= 0.2 seconds")
    return args


def producer(config, scenario, index):
    """单生产者固定身份；多生产者轮转身份，同时把同一生产者请求分发到不同节点。"""
    tokens = config.get("service_tokens") or [config["service_token"]]
    multiple = scenario.startswith("write_many_producers")
    chosen = (
        (index // len(config.get("base_urls", [config["base_url"]]))) % len(tokens)
        if multiple
        else 0
    )
    return tokens[chosen]


def notification(user: str, key: str) -> dict:
    """生成固定大小的合成通知载荷，每次发送使用独立测试幂等键。"""
    return {
        "recipient_id": user,
        "client_message_id": key,
        "type": "system.notice",
        "payload": {"title": "并发测试", "text": "x" * 128, "benchmark_id": key},
    }


def auth(token: str) -> dict:
    """构造仅用于请求的认证头，不把凭证写入结果。"""
    return {"Authorization": "Bearer " + token}


def endpoint(config, index):
    """按请求序号轮流选择服务节点，不依赖入口负载均衡器。"""
    urls = config.get("base_urls", [config["base_url"]])
    return urls[index % len(urls)]


async def batch(http, config, scenario, concurrency, count, cursors, duration=0):
    """恰好 concurrency 个闭环 worker；池等待、网络和响应读取均计入延迟。"""
    samples, errors = [], Counter()
    index = 0
    deadline = time.perf_counter() + duration if duration else None
    run_id = str(uuid.uuid4())

    async def worker():
        nonlocal index
        while (deadline is not None and time.perf_counter() < deadline) or (
            deadline is None and index < count
        ):
            current = index
            index += 1
            user_index = current % len(config["users"])
            user = config["users"][user_index]
            started = time.perf_counter_ns()
            try:
                if scenario.startswith("write_"):
                    recipient = (
                        config["users"][0]["id"]
                        if scenario.endswith("hot_user")
                        else user["id"]
                    )
                    response = await http.post(
                        endpoint(config, current) + "/api/v1/im/notifications",
                        headers=auth(producer(config, scenario, current)),
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
    await asyncio.gather(
        *(worker() for _ in range(concurrency if deadline else min(concurrency, count)))
    )
    return {
        **summarize(samples, index, time.perf_counter() - started),
        "errors": dict(errors),
        "latencies_ms": samples,
        "client_cpu_seconds": time.process_time() - cpu_started,
    }


async def ws_trial(http, config, concurrency, messages, duration=0):
    """独立账户连接；写入与 WS 落在不同节点，计时包含建连与末尾排空。"""
    run_id = str(uuid.uuid4())
    starts, latencies = {}, []
    ready = asyncio.Event()
    connected = attempted = 0
    errors, connection_errors = Counter(), Counter()
    connection_failures = []
    deadline = None

    async def connection(index):
        nonlocal connected, attempted, deadline
        user = config["users"][index]
        session = await http.post(
            endpoint(config, index) + "/api/v1/im/session",
            headers=auth(user["token"]),
            json={"device_id": f"benchmark-{index + config.get('user_offset', 0)}"},
        )
        session.raise_for_status()
        state = await http.get(
            endpoint(config, index) + "/api/v1/im/state", headers=auth(user["token"])
        )
        state.raise_for_status()
        base = httpx.URL(endpoint(config, index + 1))
        url = base.copy_with(
            scheme="wss" if base.scheme == "https" else "ws",
            path=base.path.rstrip("/") + "/api/v1/im/ws",
            params={
                "ticket": session.json()["data"]["ticket"],
                "cursor": state.json()["data"]["cursor"],
            },
        )
        async with connect(str(url), max_size=2**20, max_queue=4) as websocket:
            connected += 1
            if connected == concurrency:
                deadline = time.perf_counter() + duration if duration else None
                ready.set()
            await ready.wait()
            complete, finished = asyncio.Event(), asyncio.Event()
            observed, sent = set(), set()

            async def receive():
                async for raw in websocket:
                    page = json.loads(raw)["data"]
                    for event in page["events"]:
                        key = (
                            event.get("data", {}).get("payload", {}).get("benchmark_id")
                        )
                        if key in sent and key not in observed:
                            observed.add(key)
                            latencies.append(
                                (time.perf_counter_ns() - starts.pop(key)) / 1e6
                            )
                    await websocket.send(json.dumps({"cursor": page["cursor"]}))
                    if finished.is_set() and observed == sent:
                        complete.set()
                        return

            receiver = asyncio.create_task(receive())
            try:
                number = 0
                while (deadline is not None and time.perf_counter() < deadline) or (
                    deadline is None and number < messages
                ):
                    key = f"{run_id}-{index}-{number}"
                    sent.add(key)
                    starts[key] = time.perf_counter_ns()
                    attempted += 1
                    response = await http.post(
                        endpoint(config, index) + "/api/v1/im/notifications",
                        headers=auth(producer(config, "write_many_users", number)),
                        json=notification(user["id"], key),
                    )
                    if response.status_code != 201:
                        errors[f"HTTP_{response.status_code}"] += 1
                        raise RuntimeError("WebSocket benchmark send failed")
                    number += 1
                    if receiver.done():
                        receiver.result()
                        raise ConnectionError(
                            "WebSocket receiver ended before sending completed"
                        )
                finished.set()
                if observed == sent:
                    complete.set()

                async def drain():
                    completed = asyncio.create_task(complete.wait())
                    try:
                        await asyncio.wait(
                            (completed, receiver), return_when=asyncio.FIRST_COMPLETED
                        )
                        if receiver.done():
                            receiver.result()
                        if not complete.is_set():
                            raise ConnectionError(
                                "WebSocket receiver ended with unconfirmed deliveries"
                            )
                    finally:
                        completed.cancel()
                        await asyncio.gather(completed, return_exceptions=True)

                await asyncio.wait_for(drain(), timeout=30)
            finally:
                ended = receiver.done()
                receiver.cancel()
                outcome = (await asyncio.gather(receiver, return_exceptions=True))[0]
                if observed != sent or (ended and isinstance(outcome, Exception)):
                    detail = {
                        "connection": index,
                        "sent": len(sent),
                        "observed": len(observed),
                        "unconfirmed": len(sent - observed),
                        "receiver_ended": ended,
                        "receiver_error": type(outcome).__name__
                        if isinstance(outcome, Exception)
                        else None,
                        "close_code": getattr(websocket, "close_code", None),
                    }
                    for side in ("rcvd", "sent"):
                        frame = getattr(outcome, side, None)
                        detail[f"close_{side}_code"] = getattr(frame, "code", None)
                    detail["keepalive_timeout"] = any(
                        getattr(getattr(outcome, side, None), "reason", None)
                        == "keepalive ping timeout"
                        for side in ("rcvd", "sent")
                    )
                    connection_failures.append(detail)

    cpu_started, started = time.process_time(), time.perf_counter()
    try:
        outcomes = await asyncio.wait_for(
            asyncio.gather(
                *(connection(i) for i in range(concurrency)), return_exceptions=True
            ),
            timeout=duration + 90,
        )
        for outcome in outcomes:
            if isinstance(outcome, Exception):
                connection_errors[type(outcome).__name__] += 1
    except TimeoutError:
        connection_errors["ROUND_TIMEOUT"] += 1
    unconfirmed = attempted - len(latencies) - sum(errors.values())
    if unconfirmed:
        errors["WS_DELIVERY_UNCONFIRMED"] += unconfirmed
    return {
        **summarize(latencies, attempted, time.perf_counter() - started),
        "connection_errors": dict(connection_errors),
        "connection_failures": connection_failures,
        "errors": dict(errors),
        "latencies_ms": latencies,
        "client_cpu_seconds": time.process_time() - cpu_started,
    }


async def measure(config, args, checkpoint=None):
    """准备历史并测量选定负载；每轮观测完成后通过 checkpoint 保存已完成结果。"""
    rows = []

    def record(row):
        rows.append(row)
        if checkpoint is not None:
            checkpoint(rows)

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
                    headers=auth(producer(config, "write_many_users", i)),
                    json=notification(user["id"], f"seed-{user['id']}-{i}"),
                )
                response.raise_for_status()

        await asyncio.gather(*(seed(user) for user in config["users"]))
        for repeat in range(args.repeats):
            for scenario in args.scenarios:
                if scenario == "websocket_delivery":
                    continue
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
                        http,
                        config,
                        scenario,
                        concurrency,
                        args.warmup,
                        cursors,
                        args.warmup_seconds,
                    )
                    if warm["failed"]:
                        raise RuntimeError(f"warmup failed: {warm['errors']}")
                    row = await observe(
                        config,
                        lambda scenario=scenario, concurrency=concurrency, cursors=cursors: (
                            batch(
                                http,
                                config,
                                scenario,
                                concurrency,
                                args.requests,
                                cursors,
                                args.duration,
                            )
                        ),
                        args.sample_interval,
                    )
                    record(
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
            if "websocket_delivery" not in args.scenarios:
                continue
            for concurrency in args.concurrency:
                warm = await ws_trial(http, config, concurrency, 1)
                # 预热错误单独保存，不能冒充测量期间的消息失败。

                row = await observe(
                    config,
                    lambda concurrency=concurrency: ws_trial(
                        http, config, concurrency, args.ws_messages, args.duration
                    ),
                    args.sample_interval,
                )
                record(
                    {
                        "scenario": "websocket_delivery",
                        "warmup_errors": warm["errors"],
                        "warmup_connection_errors": warm["connection_errors"],
                        "warmup_connection_failures": warm["connection_failures"],
                        "concurrency": concurrency,
                        "repeat": repeat + 1,
                        **row,
                    }
                )
                print(
                    f"websocket_delivery connections={concurrency}: p99={row['p99_ms']} ms, errors={row['failed']}, connection_errors={row['connection_errors']}",
                    flush=True,
                )
    return rows


def write_result(path: Path, result: dict):
    """原子保存已完成轮次；.json.gz 使用无时间戳压缩，结果只含测量元数据。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode()
    if path.suffix == ".gz":
        payload = gzip.compress(payload, compresslevel=3, mtime=0)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(payload)
    temporary.replace(path)


def main(argv=None):
    """选择本地夹具或外部接入文件，输出不含凭证的结果并报告测量失败。"""
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
        "duration_seconds": args.duration,
        "warmup_seconds": args.warmup_seconds,
        "scenarios": args.scenarios,
        "measurement_mode": "closed-loop-duration"
        if args.duration
        else "closed-loop-count",
        "telemetry_interval_seconds": args.sample_interval,
        "load_generator_host": platform.node(),
        "placement": "external-manifest-unverified-host"
        if args.config
        else "same-host-independent-jvms",
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
    fixture = (
        nullcontext((validate_manifest(json.loads(args.config.read_text())), None))
        if args.config
        else server(args)
    )
    with fixture as (config, process):
        if args.serve_only:
            print(
                "Fixture ready; copy the private config.json to the load generator. Ctrl-C stops all JVMs and the temporary database.",
                flush=True,
            )
            try:
                process.wait()
            except KeyboardInterrupt:
                return
            raise SystemExit("benchmark fixture stopped")
        if max(args.concurrency) > len(config["users"]):
            raise ValueError("manifest has fewer users than requested concurrency")
        if (
            any(name.startswith("write_many_producers") for name in args.scenarios)
            and len(config["service_tokens"]) < 2
        ):
            raise ValueError("multi-producer scenarios require at least two identities")
        if (
            any(name.endswith("many_users") for name in args.scenarios)
            and len(config["users"]) < 2
        ):
            raise ValueError("multi-recipient scenarios require at least two users")
        metadata["active_users"] = len(config["users"])
        metadata["producer_identities"] = len(config["service_tokens"])
        metadata["server"] = config["server"]
        if any(
            config["server"]["postgres"][name] != "on"
            for name in ("fsync", "synchronous_commit", "full_page_writes")
        ):
            raise RuntimeError("durability settings must be on for this baseline")

        def checkpoint(rows):
            write_result(args.output, {"metadata": metadata, "trials": rows})

        rows = asyncio.run(measure(config, args, checkpoint))
    write_result(args.output, {"metadata": metadata, "trials": rows})
    print(f"Result written: {args.output}")
    if any(row.get("observation_errors") for row in rows) or (
        args.duration >= 2 * args.sample_interval
        and any(not row["diagnostics"].get("complete") for row in rows)
    ):
        raise SystemExit("benchmark diagnostics incomplete; inspect saved observations")
    if any(
        row["failed"]
        or row.get("connection_errors")
        or row.get("warmup_errors")
        or row.get("warmup_connection_errors")
        for row in rows
    ):
        raise SystemExit(
            "benchmark completed with request failures; inspect raw result"
        )


if __name__ == "__main__":
    main()
