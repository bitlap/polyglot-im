import asyncio
import copy
import json

import httpx
import pytest

from benchmark.run import batch, parse_args
from benchmark.runtime import validate_manifest
from benchmark.telemetry import summarize_observations


def test_matrix_separates_producer_and_recipient_contention():
    async def run():
        seen = []

        def respond(request):
            seen.append(
                (
                    request.url.host,
                    request.headers["authorization"],
                    json.loads(request.content)["recipient_id"],
                )
            )
            return httpx.Response(201, json={"code": 200, "data": {"message_id": "id"}})

        config = {
            "base_url": "http://a",
            "base_urls": ["http://a", "http://b"],
            "service_tokens": ["p0", "p1", "p2"],
            "users": [{"id": f"u{i}"} for i in range(4)],
        }
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            for scenario, producers, recipients in [
                ("write_many_users", 1, 4),
                ("write_hot_user", 1, 1),
                ("write_many_producers_many_users", 3, 4),
                ("write_many_producers_hot_user", 3, 1),
            ]:
                seen.clear()
                result = await batch(http, config, scenario, 4, 24, [])
                assert result["succeeded"] == 24
                assert len({row[1] for row in seen}) == producers
                assert len({row[2] for row in seen}) == recipients
                # 同一生产者不能只命中一个节点，否则会掩盖跨实例的身份协调。
                for token in {row[1] for row in seen}:
                    assert {row[0] for row in seen if row[1] == token} == {"a", "b"}

    asyncio.run(run())


def test_duration_mode_runs_to_deadline_instead_of_request_count():
    async def run():
        async def respond(request):
            await asyncio.sleep(0.001)
            return httpx.Response(201, json={"code": 200, "data": {"message_id": "id"}})

        config = {
            "base_url": "http://a",
            "service_tokens": ["p0"],
            "users": [{"id": "u"}],
        }
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            result = await batch(http, config, "write_hot_user", 2, None, [], 0.03)
        assert result["attempted"] >= 2
        assert result["elapsed_seconds"] >= 0.03
        assert result["failed"] == 0

    asyncio.run(run())


def test_duration_and_fixed_total_budgets_are_explicit():
    args = parse_args(["--instances", "2", "--db-connections", "8", "--duration", "60"])
    assert args.duration == 60 and args.requests is None
    assert args.db_connections == 8 and args.server_cpus == 4
    assert parse_args(["--requests", "300"]).duration == 0
    assert parse_args(["--requests", "300"]).warmup_seconds == 0
    for arguments in [
        ["--duration", "nan"],
        ["--duration", "inf"],
        ["--instances", "2", "--db-connections", "1"],
        ["--instances", "2", "--server-cpus", "1"],
        ["--duration", "1", "--requests", "100"],
        ["--sample-interval", "0.01"],
    ]:
        with pytest.raises(SystemExit):
            parse_args(arguments)


def test_manifest_rejects_shared_jvm_and_connection_budget_drift():
    manifest = {
        "manifest_version": 2,
        "base_urls": ["http://a", "http://b"],
        "users": [{}],
        "service_tokens": ["synthetic"],
        "diagnostics_url": "http://a/metrics",
        "server": {
            "instances": 2,
            "database_connections_total": 8,
            "nodes": [{"pid": 1, "pool": 4}, {"pid": 2, "pool": 4}],
        },
    }
    assert validate_manifest(manifest) is manifest
    shared = copy.deepcopy(manifest)
    shared["server"]["nodes"][1]["pid"] = 1
    with pytest.raises(ValueError, match="distinct JVM"):
        validate_manifest(shared)
    drift = copy.deepcopy(manifest)
    drift["server"]["nodes"][1]["pool"] = 8
    with pytest.raises(ValueError, match="budget"):
        validate_manifest(drift)
    invalid = copy.deepcopy(manifest)
    invalid["base_urls"][0] = "http://user:password@a"
    with pytest.raises(ValueError, match="HTTP"):
        validate_manifest(invalid)


def sample(timestamp, cpu, acquired, acquire_ns, blocked, wal):
    return {
        "timestamp_ms": timestamp,
        "nodes": [
            {
                "pid": 1,
                "process_cpu_nanos": cpu,
                "gc_millis": 10,
                "pool": {
                    "acquisitions": acquired,
                    "acquire_nanos": acquire_ns,
                    "timeouts": 0,
                    "waiting": 2,
                },
            }
        ],
        "postgres": {
            "process_cpu_nanos": {"10": cpu // 2},
            "activity": [{"blocked": str(blocked)}],
            "wal": [
                {"wal_bytes": str(wal), "wal_sync_time": "1", "stats_reset": "same"}
            ],
        },
    }


def test_diagnostics_uses_counter_deltas_and_does_not_invent_missing_samples():
    result = summarize_observations(
        [
            sample(1000, 0, 10, 100, 0, 100),
            sample(2000, 1_000_000_000, 12, 4_000_100, 3, 300),
        ]
    )
    assert result["server_cpu_cores"] == 1
    assert result["postgres_cpu_cores_observed"] == 0.5
    assert result["pool_acquire_mean_ms"] == 2
    assert result["blocked_backends_peak"] == 3 and result["wal_bytes"] == 200
    assert summarize_observations([])["complete"] is False
    changed = sample(2000, 0, 0, 0, 0, 0)
    changed["nodes"][0]["pid"] = 2
    assert (
        summarize_observations([sample(1000, 0, 0, 0, 0, 0), changed])["node_restart"]
        is True
    )


def test_report_distinguishes_resource_budgets_and_missing_observations():
    from pathlib import Path

    from benchmark.report import render

    root = Path(__file__).resolve().parents[2]
    legacy = json.loads(
        (root / "benchmark/results/distributed-single-2026-11-11.json").read_text()
    )
    result = {
        "metadata": copy.deepcopy(legacy["metadata"]),
        "trials": [
            {
                "scenario": "history",
                "concurrency": 1,
                "repeat": 1,
                "attempted": 2,
                "elapsed_seconds": 1,
                "latencies_ms": [],
                "successful_rps": 0,
                "diagnostics": {"complete": False},
            }
        ],
    }
    assert "未采集" in render(result, "result.json")
    result["metadata"]["duration_seconds"] = 60
    result["metadata"]["warmup_seconds"] = 10
    result["metadata"]["server"].update(
        {
            "nodes": [{"pid": 1}, {"pid": 2}],
            "database_connections_total": 8,
            "database_pool_per_node": [4, 4],
            "server_cpus_total": 4,
            "server_heap_mib_total": 2048,
        }
    )
    text = render(result, "result.json")
    assert "独立 JVM 2 个" in text and "业务池总预算 8" in text
    assert "至少 60 秒" in text and "完整采样" in text
    result["trials"][0]["diagnostics"].update(
        {
            "postgres_cpu_seconds_observed": 31.82,
            "wal_write_millis": 6491.41,
            "postgres_active_wait_estimated_millis": {"Lock/transactionid": 2000},
        }
    )
    text = render(result, "result.json")
    assert "31.82" in text and "6491.41" in text
    assert "Lock/transactionid: 2000.00" in text
    assert "CPU 秒（观测）" in text and "active 等待估算" in text


def test_missing_postgres_cpu_is_unknown_instead_of_zero():
    values = [sample(1000, 0, 0, 0, 0, 0), sample(2000, 10, 1, 1, 0, 1)]
    for value in values:
        value["postgres"]["process_cpu_nanos"] = {}
    diagnostic = summarize_observations(values)
    assert diagnostic["postgres_cpu_cores_observed"] is None
    assert diagnostic["complete"] is False


def test_remote_sampling_does_not_require_synchronized_client_wall_clock(monkeypatch):
    from benchmark import telemetry

    calls = []

    def respond(request):
        calls.append(request.headers["Authorization"])
        value = sample(
            len(calls) * 1000, len(calls) * 10, len(calls), len(calls), 0, len(calls)
        )
        for node in value["nodes"]:
            node["timestamp_ms"] = value["timestamp_ms"]
        return httpx.Response(200, json=value)

    client = httpx.AsyncClient(transport=httpx.MockTransport(respond))
    monkeypatch.setattr(telemetry.httpx, "AsyncClient", lambda **kwargs: client)

    async def operation():
        return {"attempted": 1}

    result = asyncio.run(
        telemetry.observe(
            {
                "diagnostics_url": "http://metrics/metrics",
                "diagnostics_token": "private-test",
            },
            operation,
            10,
        )
    )
    assert result["diagnostics"]["complete"]
    assert not result["observation_errors"]
    assert calls == ["Bearer private-test", "Bearer private-test"]
    assert "private-test" not in json.dumps(result)


def test_sampling_errors_remain_visible_in_results(monkeypatch):
    from benchmark import telemetry

    client = httpx.AsyncClient(
        transport=httpx.MockTransport(lambda request: httpx.Response(503))
    )
    monkeypatch.setattr(telemetry.httpx, "AsyncClient", lambda **kwargs: client)

    async def operation():
        return {"attempted": 1}

    result = asyncio.run(
        telemetry.observe(
            {"diagnostics_url": "http://metrics/metrics", "diagnostics_token": "test"},
            operation,
            10,
        )
    )
    assert result["diagnostics"]["complete"] is False
    assert len(result["observation_errors"]) == 2
    assert result["observations"] == []


def test_database_timing_counters_and_active_wait_integration():
    values = [sample(1000, 0, 0, 0, 0, 0), sample(3000, 2_000_000_000, 1, 1, 0, 1)]
    for index, value in enumerate(values):
        value["postgres"]["database"] = [
            {
                "blk_read_time": str(10 + index * 4),
                "blk_write_time": str(20 + index * 6),
                "stats_reset": "same",
            }
        ]
        value["postgres"]["wal"][0]["wal_write_time"] = str(100 + index * 30)
        value["postgres"]["activity"] = [
            {
                "state": "active",
                "wait_event_type": "Lock",
                "wait_event": "transactionid",
                "connections": str(index * 2),
                "blocked": str(index * 2),
            },
            {
                "state": "idle",
                "wait_event_type": "Client",
                "wait_event": "ClientRead",
                "connections": "100",
                "blocked": "0",
            },
        ]
    result = summarize_observations(values)
    assert result["postgres_cpu_seconds_observed"] == 1
    assert result["database_read_millis"] == 4
    assert result["database_write_millis"] == 6
    assert result["wal_write_millis"] == 30
    assert result["postgres_active_wait_estimated_millis"] == {
        "Lock/transactionid": 2000
    }
    values[1]["postgres"]["database"][0]["stats_reset"] = "reset"
    assert summarize_observations(values)["database_read_millis"] is None
    values[1]["postgres"]["wal"][0]["wal_write_time"] = "0"
    assert summarize_observations(values)["wal_write_millis"] is None
    values[0]["postgres"]["activity"] = [{"blocked": "0"}]
    assert (
        summarize_observations(values)["postgres_active_wait_estimated_millis"] is None
    )
