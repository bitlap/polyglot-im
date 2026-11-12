"""在测量窗口采集 JVM、连接池及 PostgreSQL 快照，不保存认证信息。"""

import asyncio
import time
from itertools import pairwise

import httpx


def counter_delta(samples: list[dict], group: str, field: str) -> float | None:
    """求同一统计周期内的累计耗时差值；字段缺失或任一快照重置时返回未知。"""
    rows = [next(iter(sample["postgres"].get(group, [])), {}) for sample in samples]
    if any(field not in row or "stats_reset" not in row for row in rows):
        return None
    if len({row["stats_reset"] for row in rows}) != 1:
        return None
    values = [float(row[field]) for row in rows]
    if any(after < before for before, after in pairwise(values)):
        return None
    return values[-1] - values[0]


def estimated_wait_millis(samples: list[dict]) -> dict[str, float] | None:
    """对 active 后端等待数量作梯形积分，返回各类型/事件的估算后端毫秒。"""
    snapshots = []
    for sample in samples:
        counts = {}
        for row in sample["postgres"]["activity"]:
            if (
                not {"state", "wait_event_type", "wait_event", "connections"}
                <= row.keys()
            ):
                return None
            if row["state"] == "active" and row["wait_event_type"]:
                key = f"{row['wait_event_type']}/{row['wait_event']}"
                counts[key] = counts.get(key, 0) + int(row["connections"])
        snapshots.append((sample["timestamp_ms"], counts))
    totals = {}
    for (before_ms, before), (after_ms, after) in pairwise(snapshots):
        for event in before.keys() | after.keys():
            totals[event] = totals.get(event, 0) + (
                (before.get(event, 0) + after.get(event, 0))
                / 2
                * (after_ms - before_ms)
            )
    return totals


def summarize_observations(samples: list[dict]) -> dict:
    """从累计计数器计算 CPU、GC 和连接获取耗时差值，并保留采样等待峰值。"""
    if len(samples) < 2:
        return {"samples": len(samples), "complete": False}
    elapsed = (samples[-1]["timestamp_ms"] - samples[0]["timestamp_ms"]) / 1000
    if elapsed <= 0:
        return {"samples": len(samples), "complete": False}
    first = {node["pid"]: node for node in samples[0]["nodes"]}
    last = {node["pid"]: node for node in samples[-1]["nodes"]}
    if first.keys() != last.keys():
        return {"samples": len(samples), "complete": False, "node_restart": True}
    cpu = gc_ms = acquisitions = acquire_ns = timeouts = 0
    for pid, before in first.items():
        after = last[pid]
        cpu += after["process_cpu_nanos"] - before["process_cpu_nanos"]
        gc_ms += after["gc_millis"] - before["gc_millis"]
        for key in ("acquisitions", "acquire_nanos", "timeouts"):
            delta = after["pool"][key] - before["pool"][key]
            if key == "acquisitions":
                acquisitions += delta
            elif key == "acquire_nanos":
                acquire_ns += delta
            else:
                timeouts += delta
    before_wal = samples[0]["postgres"]["wal"][0]
    after_wal = samples[-1]["postgres"]["wal"][0]
    wal_reset = before_wal["stats_reset"] != after_wal["stats_reset"]
    db_cpu = 0
    cpu_pairs = 0
    for before, after in pairwise(samples):
        a, b = (
            before["postgres"]["process_cpu_nanos"],
            after["postgres"]["process_cpu_nanos"],
        )
        common = a.keys() & b.keys()
        cpu_pairs += len(common)
        db_cpu += sum(max(0, b[pid] - a[pid]) for pid in common)
    return {
        "samples": len(samples),
        "complete": not wal_reset and cpu_pairs > 0,
        "elapsed_seconds": elapsed,
        "server_cpu_cores": cpu / 1e9 / elapsed,
        "postgres_cpu_seconds_observed": db_cpu / 1e9 if cpu_pairs else None,
        "postgres_active_wait_estimated_millis": estimated_wait_millis(samples),
        "database_read_millis": counter_delta(samples, "database", "blk_read_time"),
        "database_write_millis": counter_delta(samples, "database", "blk_write_time"),
        "wal_write_millis": counter_delta(samples, "wal", "wal_write_time"),
        "postgres_cpu_cores_observed": db_cpu / 1e9 / elapsed if cpu_pairs else None,
        "gc_millis": gc_ms,
        "pool_acquisitions": acquisitions,
        "pool_acquire_mean_ms": acquire_ns / acquisitions / 1e6
        if acquisitions
        else None,
        "pool_timeouts": timeouts,
        "pool_waiting_peak": max(
            sum(n["pool"]["waiting"] for n in sample["nodes"]) for sample in samples
        ),
        "blocked_backends_peak": max(
            sum(int(row["blocked"]) for row in sample["postgres"]["activity"])
            for sample in samples
        ),
        "wal_bytes": None
        if wal_reset
        else int(after_wal["wal_bytes"]) - int(before_wal["wal_bytes"]),
        "wal_sync_millis": counter_delta(samples, "wal", "wal_sync_time"),
    }


async def observe(config, operation, interval=1.0):
    """用独立 HTTP 连接采样一次测量；采样错误显式记录，不能冒充零等待。"""
    samples, errors = [], []
    stopped = asyncio.Event()
    last_change = time.monotonic()
    async with httpx.AsyncClient(timeout=5, trust_env=False) as http:

        async def snapshot():
            nonlocal last_change
            try:
                response = await http.get(
                    config["diagnostics_url"],
                    headers={"Authorization": "Bearer " + config["diagnostics_token"]},
                )
                response.raise_for_status()
                value = response.json()
                unchanged = (
                    samples and value["timestamp_ms"] == samples[-1]["timestamp_ms"]
                )
                if (unchanged and time.monotonic() - last_change > 5) or any(
                    value["timestamp_ms"] - node["timestamp_ms"] > 5000
                    for node in value["nodes"]
                ):
                    raise ValueError("stale benchmark diagnostics")
                if not unchanged:
                    last_change = time.monotonic()
                    samples.append(value)
            except (httpx.HTTPError, ValueError, KeyError):
                errors.append(
                    {
                        "at_monotonic": time.monotonic(),
                        "error": "DIAGNOSTICS_UNAVAILABLE",
                    }
                )

        async def poll():
            while not stopped.is_set():
                try:
                    await asyncio.wait_for(stopped.wait(), timeout=interval)
                except TimeoutError:
                    await snapshot()

        await snapshot()
        task = asyncio.create_task(poll())
        try:
            result = await operation()
        finally:
            stopped.set()
            await task
            await snapshot()
    result["observations"] = samples
    result["observation_errors"] = errors
    result["diagnostics"] = summarize_observations(samples)
    return result
