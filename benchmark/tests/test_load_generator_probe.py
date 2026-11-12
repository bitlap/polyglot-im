import pytest

from benchmark import load_generator_probe as probe


def test_worker_merge_uses_global_wall_time_raw_latencies_and_cpu_sum():
    rows = [
        {
            "attempted": 3,
            "latencies_ms": [1, 2],
            "errors": {"HTTP_500": 1},
            "client_cpu_seconds": 0.5,
            "diagnostics": {"wal_bytes": 100},
        },
        {
            "attempted": 2,
            "latencies_ms": [3, 100],
            "errors": {},
            "client_cpu_seconds": 0.75,
            "diagnostics": {"wal_bytes": 100},
        },
    ]
    result = probe.merge_worker_results(rows, 2, 2)
    assert result["successful_rps"] == 2
    assert result["attempted"] == 5 and result["failed"] == 1
    assert result["p99_ms"] == 100
    assert result["client_cpu_seconds"] == 1.25
    assert result["errors"] == {"HTTP_500": 1}
    assert "diagnostics" not in result  # 父进程 observe 只附加一次数据库观测。
    assert result["worker_rows"] is rows


def test_incomplete_worker_set_is_not_misrepresented_as_a_complete_probe():
    with pytest.raises(ValueError):
        probe.merge_worker_results([], 1, 4)
    with pytest.raises(ValueError):
        probe.merge_worker_results([{"error": "TimeoutError"}], 1, 1)


def test_probe_cannot_silently_change_requested_total_concurrency():
    import asyncio

    with pytest.raises(ValueError):
        asyncio.run(probe.round_probe({}, 3, 32, 1))
    with pytest.raises(ValueError):
        asyncio.run(probe.round_probe({}, 0, 32, 1))
