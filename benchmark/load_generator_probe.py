"""固定服务与总并发，对照单进程和四进程发压；数据库指标由父进程统一采样。"""

import asyncio
import multiprocessing
import os
import time
from collections import Counter
from pathlib import Path

import httpx

from benchmark.metrics import summarize
from benchmark.run import auth, batch, notification, producer
from benchmark.telemetry import observe


def merge_worker_results(rows, elapsed_seconds, process_count):
    """合并子进程延迟与 CPU，使用父进程整体耗时，不叠加数据库观测。"""
    if len(rows) != process_count or any("error" in row for row in rows):
        raise ValueError("all probe workers must return complete measurement rows")
    latencies = [value for row in rows for value in row["latencies_ms"]]
    errors = Counter()
    for row in rows:
        errors.update(row["errors"])
    return {
        **summarize(latencies, sum(row["attempted"] for row in rows), elapsed_seconds),
        "errors": dict(errors),
        "latencies_ms": latencies,
        "client_cpu_seconds": sum(row["client_cpu_seconds"] for row in rows),
        "worker_rows": rows,
        "load_generator_processes": process_count,
    }


def worker(pipe, config, concurrency, duration, scenario):
    """保持同一 HTTP 连接池预热和测量，通过管道等待统一开始信号。"""

    async def run():
        limits = httpx.Limits(
            max_connections=concurrency, max_keepalive_connections=concurrency
        )
        async with httpx.AsyncClient(
            limits=limits, timeout=30, trust_env=False
        ) as http:
            warm = await batch(http, config, scenario, concurrency, None, [], 10)
            if warm["failed"]:
                raise RuntimeError("probe warmup failed")
            pipe.send({"ready": True})
            if await asyncio.to_thread(pipe.recv) != "measure":
                raise RuntimeError("invalid probe synchronization")
            result = await batch(
                http, config, scenario, concurrency, None, [], duration
            )
            result["worker_pid"] = os.getpid()
            pipe.send(result)

    try:
        asyncio.run(run())
    except (
        OSError,
        TimeoutError,
        RuntimeError,
        httpx.HTTPError,
        ValueError,
        EOFError,
    ) as error:
        pipe.send({"error": type(error).__name__})
    finally:
        pipe.close()


async def seed(config):
    """与正式基准相同，为所有测试账户预置 20 条带 Outbox 的通知。"""
    async with httpx.AsyncClient(
        base_url=config["base_url"], timeout=30, trust_env=False
    ) as http:

        async def user_seed(user):
            for index in range(20):
                response = await http.post(
                    "/api/v1/im/notifications",
                    headers=auth(producer(config, "write_many_users", index)),
                    json=notification(user["id"], f"seed-{user['id']}-{index}"),
                )
                response.raise_for_status()

        await asyncio.gather(*(user_seed(user) for user in config["users"]))


async def round_probe(
    config, processes, total_concurrency, duration, scenario="write_many_users"
):
    """各进程预热后一起测量，中央观测避免将重叠的数据库计数器相加。"""
    if processes <= 0 or total_concurrency < processes or total_concurrency % processes:
        raise ValueError(
            "total concurrency must be evenly divided among probe processes"
        )
    context = multiprocessing.get_context("spawn")
    children, pipes = [], []
    result = None
    try:
        for index in range(processes):
            parent, child = context.Pipe()
            offset = index * len(config["users"]) // processes
            local = {
                **config,
                "users": config["users"][offset:] + config["users"][:offset],
            }
            process = context.Process(
                target=worker,
                args=(child, local, total_concurrency // processes, duration, scenario),
            )
            process.start()
            child.close()
            children.append(process)
            pipes.append(parent)
        ready = await asyncio.wait_for(
            asyncio.gather(*(asyncio.to_thread(pipe.recv) for pipe in pipes)),
            timeout=90,
        )
        if any(value != {"ready": True} for value in ready):
            raise RuntimeError(f"probe worker preparation failed: {ready}")

        async def operation():
            started = time.perf_counter()
            for pipe in pipes:
                pipe.send("measure")
            rows = await asyncio.wait_for(
                asyncio.gather(*(asyncio.to_thread(pipe.recv) for pipe in pipes)),
                timeout=duration + 60,
            )
            if any("error" in row for row in rows):
                raise RuntimeError("probe worker measurement failed")
            elapsed = time.perf_counter() - started
            return merge_worker_results(rows, elapsed, processes)

        result = await observe(config, operation, interval=1)
        return result
    finally:
        for pipe in pipes:
            pipe.close()
        for process in children:
            await asyncio.to_thread(process.join, 5)
            if process.is_alive():
                process.terminate()
                await asyncio.to_thread(process.join, 5)
            if process.is_alive():
                process.kill()
                await asyncio.to_thread(process.join)
        if result is not None:
            result["worker_exit_codes"] = {
                str(process.pid): process.exitcode for process in children
            }


def main():
    """运行同总并发的发压进程对照；记录原始快照、子进程统计与脚本哈希。"""
    import argparse
    import hashlib
    import platform
    import subprocess
    from datetime import UTC, datetime

    from benchmark.report import read_result
    from benchmark.run import ROOT, parse_args, write_result
    from benchmark.runtime import server

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--scenario",
        choices=["write_many_users", "history"],
        default="write_many_users",
    )
    options = parser.parse_args()
    args = parse_args(
        [
            "--instances",
            "1",
            "--db-connections",
            "8",
            "--server-cpus",
            "4",
            "--server-heap-mib",
            "2048",
            "--users",
            "256",
            "--producers",
            "16",
            "--duration",
            "60",
            "--warmup-seconds",
            "10",
            "--repeats",
            "3",
            "--concurrency",
            "32",
            "--scenarios",
            options.scenario,
        ]
    )
    baseline_metadata = read_result(options.baseline)["metadata"]
    if baseline_metadata["load_generator_host"] != platform.node():
        raise ValueError(
            "probe baseline must come from the current load-generator host"
        )
    current_server_tree = subprocess.check_output(
        ["git", "rev-parse", "HEAD:scala-server"], cwd=ROOT, text=True
    ).strip()
    baseline_server_tree = subprocess.check_output(
        ["git", "rev-parse", baseline_metadata["git_revision"] + ":scala-server"],
        cwd=ROOT,
        text=True,
    ).strip()
    if current_server_tree != baseline_server_tree:
        raise ValueError("probe requires the same server source tree as its baseline")
    metadata = {
        **baseline_metadata,
        "git_revision": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "working_tree_dirty": bool(
            subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)
        ),
        "os": platform.platform(),
        "python": platform.python_version(),
        "timestamp_utc": datetime.now(UTC).isoformat(),
        "measurement_mode": "load-generator-process-comparison",
        "server_source_tree": current_server_tree,
        "baseline_server_source_tree": baseline_server_tree,
        "baseline_git_revision": baseline_metadata["git_revision"],
        "baseline_file_sha256": hashlib.sha256(
            options.baseline.read_bytes()
        ).hexdigest(),
        "scenarios": [options.scenario],
        "concurrency": [32],
        "duration_seconds": 60,
        "warmup_seconds": 10,
        "load_generator_process_counts": [1, 4],
        "probe_source_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "load_generator": "multiprocessing spawn; each process owns an HTTPX AsyncClient; aggregate concurrency 32",
    }
    rows = []
    with server(args) as (config, _):
        metadata["server"] = config["server"]

        async def measure():
            await seed(config)
            for repeat in range(1, 4):
                for processes in [1, 4] if repeat % 2 else [4, 1]:
                    row = await round_probe(config, processes, 32, 60, options.scenario)
                    rows.append(
                        {
                            "scenario": options.scenario,
                            "concurrency": 32,
                            "repeat": repeat,
                            **row,
                        }
                    )
                    write_result(options.output, {"metadata": metadata, "trials": rows})
                    print(
                        f"processes={processes} repeat={repeat} rps={row['successful_rps']:.1f} "
                        f"p99={row['p99_ms']:.2f} client_cores={row['client_cpu_seconds'] / row['elapsed_seconds']:.2f} "
                        f"failed={row['failed']}",
                        flush=True,
                    )

        asyncio.run(measure())
    if any(
        row["failed"]
        or any(code != 0 for code in row["worker_exit_codes"].values())
        or len({worker["worker_pid"] for worker in row["worker_rows"]})
        != row["load_generator_processes"]
        or row["observation_errors"]
        or not row["diagnostics"].get("complete")
        for row in rows
    ):
        raise SystemExit("probe incomplete or request errors; inspect raw result")


if __name__ == "__main__":
    main()
