"""可审计的 nearest-rank 分位数与原始样本聚合。"""

import math


def percentile(values: list[float], percent: float) -> float | None:
    if not 0 < percent <= 100:
        raise ValueError("percentile must be in (0, 100]")
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)]


def summarize(samples_ms: list[float], attempted: int, elapsed_seconds: float) -> dict:
    if elapsed_seconds <= 0 or attempted < len(samples_ms):
        raise ValueError("invalid elapsed time or sample count")
    if any(not math.isfinite(value) or value < 0 for value in samples_ms):
        raise ValueError("latencies must be finite and non-negative")
    return {
        "attempted": attempted,
        "succeeded": len(samples_ms),
        "failed": attempted - len(samples_ms),
        "error_rate": (attempted - len(samples_ms)) / attempted if attempted else 0,
        "elapsed_seconds": elapsed_seconds,
        "successful_rps": len(samples_ms) / elapsed_seconds,
        "p50_ms": percentile(samples_ms, 50),
        "p95_ms": percentile(samples_ms, 95),
        "p99_ms": percentile(samples_ms, 99),
        "max_ms": max(samples_ms, default=None),
    }


def aggregate_trials(trials: list[dict]) -> list[dict]:
    groups = {}
    for trial in trials:
        groups.setdefault((trial["scenario"], trial["concurrency"]), []).append(trial)
    rows = []
    for (scenario, concurrency), group in groups.items():
        samples = [sample for trial in group for sample in trial["latencies_ms"]]
        result = summarize(
            samples,
            sum(t["attempted"] for t in group),
            sum(t["elapsed_seconds"] for t in group),
        )
        rows.append(
            {
                "scenario": scenario,
                "concurrency": concurrency,
                "trials": len(group),
                "rps_min": min(t["successful_rps"] for t in group),
                "rps_max": max(t["successful_rps"] for t in group),
                **result,
            }
        )
    return rows
