"""用可独立核算的计数器验证矩阵分析，保留未知值与推测边界。"""

import pytest

from benchmark import matrix_report as analysis


def trial(seconds, server_cores, acquisitions, acquire_mean_ms, repeat):
    def snapshot(at, after):
        cpu = int(seconds * server_cores * 1e9) if after else 0
        return {
            "timestamp_ms": at,
            "nodes": [
                {
                    "pid": 1,
                    "process_cpu_nanos": cpu,
                    "gc_millis": 10 if after else 0,
                    "pool": {
                        "acquisitions": acquisitions if after else 0,
                        "acquire_nanos": int(acquisitions * acquire_mean_ms * 1e6)
                        if after
                        else 0,
                        "timeouts": 0,
                        "waiting": 0,
                    },
                }
            ],
            "postgres": {
                "process_cpu_nanos": {"2": cpu // 2},
                "activity": [],
                "wal": [
                    {
                        "stats_reset": "same",
                        "wal_bytes": "100" if after else "0",
                        "wal_write_time": "5" if after else "0",
                        "wal_sync_time": "0",
                    }
                ],
            },
        }

    return {
        "scenario": "history",
        "concurrency": 8,
        "repeat": repeat,
        "attempted": 2,
        "latencies_ms": [1, 2],
        "elapsed_seconds": seconds,
        "successful_rps": 2 / seconds,
        "client_cpu_seconds": 0.2 * seconds,
        "observation_errors": [],
        "observations": [snapshot(1000, False), snapshot(1000 + seconds * 1000, True)],
    }


def test_weighted_cpu_and_pool_cost_use_observation_windows_and_counts():
    result = {
        "metadata": {"server": {"instances": 1}},
        "trials": [trial(1, 1, 100, 1, 1), trial(3, 2, 300, 3, 2)],
    }
    case = analysis.case_metrics(result)[0]
    assert case["server_cpu"] == pytest.approx(1.75)
    assert case["database_cpu_seconds"] == pytest.approx(3.5)
    assert case["pool_mean_ms"] == pytest.approx(2.5)
    assert case["client_cpu"] == pytest.approx(0.2)
    assert case["wal_write_ms"] == 10
    assert case["complete"] is True


def test_unknown_cpu_is_unknown_and_does_not_create_zero_bottleneck_claim():
    t = trial(1, 1, 100, 1, 1)
    for sample in t["observations"]:
        sample["postgres"]["process_cpu_nanos"] = {}
    case = analysis.case_metrics(
        {"metadata": {"server": {"instances": 1}}, "trials": [t]}
    )[0]
    assert case["database_cpu"] is None
    assert case["database_cpu_seconds"] is None
    assert case["complete"] is False
    assert analysis.findings(case, [])[0] == "诊断不完整"


def test_internal_lwlock_is_not_reported_as_a_proven_user_row_lock():
    case = analysis.case_metrics(
        {"metadata": {"server": {"instances": 1}}, "trials": [trial(1, 1, 100, 1, 1)]}
    )[0]
    case["wait_events"] = {"LWLock/BufferContent": 100}
    status, evidence, causes = analysis.findings(case, [])
    assert "争用" in status and "LWLock" in evidence
    assert "不能仅凭 LWLock" in causes
    assert "同一生产者配额行与共享写入路径是候选" not in causes


def test_throughput_regression_and_generator_cpu_are_evidence_not_unique_root_cause():
    case = analysis.case_metrics(
        {"metadata": {"server": {"instances": 1}}, "trials": [trial(1, 1, 100, 1, 1)]}
    )[0]
    case.update(successful_rps=100, p99_ms=100, concurrency=32, client_cpu=0.9)
    lower = {**case, "concurrency": 8, "successful_rps": 200, "p99_ms": 10}
    status, evidence, causes = analysis.findings(case, [lower])
    assert "退化" in status and "下降 50%" in evidence
    assert "发压端平均 0.90 核" in evidence
    assert "可能限制供给" in causes and "当前不能排除" in causes


def test_missing_or_duplicate_matrix_trials_are_not_published(tmp_path):
    import json

    result = {
        "metadata": {"server": {"instances": 1}},
        "trials": [trial(1, 1, 10, 1, 1)],
    }
    (tmp_path / "one.json").write_text(json.dumps(result))
    index = {
        "expected": {
            "instances": [1, 2],
            "scenarios": ["history"],
            "concurrency": [8],
            "repeats": 1,
        },
        "runs": [{"result": "one.json"}],
    }
    path = tmp_path / "index.json"
    path.write_text(json.dumps(index))
    with pytest.raises(ValueError, match="coverage mismatch"):
        analysis.render_matrix(path, tmp_path / "README.md")
    index["runs"].append({"result": "one.json"})
    path.write_text(json.dumps(index))
    with pytest.raises(ValueError, match="duplicate matrix trial"):
        analysis.render_matrix(path, tmp_path / "README.md")


def test_resource_budget_mismatch_prevents_an_apparent_scaling_comparison(tmp_path):
    import json

    runs = []
    for instances, budget in [(1, 8), (2, 16)]:
        result = {
            "metadata": {
                "duration_seconds": 1,
                "warmup_seconds": 0.5,
                "active_users": 4,
                "producer_identities": 2,
                "git_revision": "fixed",
                "server": {
                    "instances": instances,
                    "database_connections_total": budget,
                    "server_cpus_total": 4,
                    "server_heap_mib_total": 2048,
                },
            },
            "trials": [trial(1, 1, 10, 1, 1)],
        }
        name = f"{instances}.json"
        (tmp_path / name).write_text(json.dumps(result))
        runs.append({"result": name})
    index = {
        "expected": {
            "instances": [1, 2],
            "scenarios": ["history"],
            "concurrency": [8],
            "repeats": 1,
        },
        "runs": runs,
    }
    path = tmp_path / "index.json"
    path.write_text(json.dumps(index))
    with pytest.raises(ValueError, match="resource/workload/revision mismatch"):
        analysis.render_matrix(path, tmp_path / "README.md")


def test_host_quality_distinguishes_unmonitored_and_excludes_startup_only_noise():
    from datetime import UTC, datetime

    result = {"trials": [trial(1, 1, 10, 1, 1)]}  # 实际观测窗口1000~2000ms。

    def point(milliseconds, external):
        return {
            "timestamp_utc": datetime.fromtimestamp(
                milliseconds / 1000, UTC
            ).isoformat(),
            "load_average": [3, 2, 1],
            "high_cpu_java": [{"benchmark_worktree": not external}] if external else [],
        }

    unknown = analysis.host_quality(result, [])
    assert unknown["external_samples"] is None and unknown["monitored_trials"] == 0
    samples = [point(500, True), point(1250, False), point(1750, False)]
    quiet = analysis.host_quality(result, samples)
    assert quiet["monitored_trials"] == 1 and quiet["external_samples"] == 0
    assert quiet["max_load_1m"] == 3
    samples.append(point(1800, True))
    assert analysis.host_quality(result, samples)["external_samples"] == 1


def test_probe_report_rejects_missing_source_or_incomplete_repeats(
    monkeypatch, tmp_path
):
    entry = {"scenario": "history", "result": "probe.json.gz"}
    result = {"metadata": {}, "trials": []}
    monkeypatch.setattr(analysis, "read_result", lambda _: result)
    with pytest.raises(ValueError, match="source tree"):
        analysis.probe_tables(
            {"probes": [entry]}, tmp_path / "index.json", tmp_path / "README.md"
        )
    result["metadata"] = {
        "server_source_tree": "same",
        "baseline_server_source_tree": "same",
    }
    result["trials"] = [
        {"load_generator_processes": count, "repeat": repeat}
        for count in [1, 4]
        for repeat in [1, 2]
    ]
    with pytest.raises(ValueError, match="three rounds"):
        analysis.probe_tables(
            {"probes": [entry]}, tmp_path / "index.json", tmp_path / "README.md"
        )


def test_probe_report_compares_separate_process_groups(monkeypatch, tmp_path):
    rows = [
        {**trial(1, 1, 1, 0.001, repeat), "load_generator_processes": count}
        for count in [1, 4]
        for repeat in [1, 2, 3]
    ]
    # 三轮各两个请求，四进程组用四分之一耗时，原始样本也分别合并。
    for row in rows:
        if row["load_generator_processes"] == 4:
            row["elapsed_seconds"] = 0.25
            row["successful_rps"] = 8
    result = {
        "metadata": {
            "server_source_tree": "same",
            "baseline_server_source_tree": "same",
            "git_revision": "tool",
            "server": {"instances": 1},
        },
        "trials": rows,
    }
    monkeypatch.setattr(analysis, "read_result", lambda _: result)
    rendered = "\n".join(
        analysis.probe_tables(
            {"probes": [{"scenario": "history", "result": "p.gz"}]},
            tmp_path / "index.json",
            tmp_path / "README.md",
        )
    )
    assert "4.00 倍" in rendered
    assert "限制了该项测得吞吐" in rendered
    assert "| 1 | 6/6 | 2.0" in rendered
    assert "| 4 | 6/6 | 8.0" in rendered


def test_ordinary_report_cannot_merge_different_generator_counts(monkeypatch):
    from benchmark import report

    with pytest.raises(ValueError, match="generator-process"):
        report.render(
            {"metadata": {"measurement_mode": "load-generator-process-comparison"}},
            "probe.gz",
        )


def test_host_interference_is_presented_as_a_limit_on_causal_claims():
    case = analysis.case_metrics(
        {"metadata": {"server": {"instances": 1}}, "trials": [trial(1, 1, 1, 0.01, 1)]}
    )[0]
    case["host_interference"] = True
    status, evidence, causes = analysis.findings(case, [])
    assert "受同机干扰" in status
    assert "外部高 CPU Java" in evidence
    assert "不能据此确认容量上限" in causes
