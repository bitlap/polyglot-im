"""汇总单/双实例矩阵，逐项列出观测证据与有边界的瓶颈推测。"""

import argparse
import os
from datetime import datetime
from pathlib import Path

from benchmark.metrics import aggregate_trials
from benchmark.report import NAMES, display, read_result, render
from benchmark.telemetry import summarize_observations


def case_metrics(result: dict) -> list[dict]:
    """合并同一场景/并发的轮次，保留错误、波动与按采样时长加权的诊断值。"""
    cases = []
    for summary in aggregate_trials(result["trials"]):
        trials = [
            trial
            for trial in result["trials"]
            if trial["scenario"] == summary["scenario"]
            and trial["concurrency"] == summary["concurrency"]
        ]
        diagnostics = [
            summarize_observations(trial.get("observations", [])) for trial in trials
        ]
        elapsed = sum(d.get("elapsed_seconds", 0) for d in diagnostics)

        def total(key, diagnostics=diagnostics):
            values = [d.get(key) for d in diagnostics]
            return sum(values) if all(value is not None for value in values) else None

        def weighted(key, diagnostics=diagnostics, elapsed=elapsed):
            values = [d.get(key) for d in diagnostics]
            return (
                sum(
                    value * d["elapsed_seconds"]
                    for value, d in zip(values, diagnostics)
                )
                / elapsed
                if elapsed and all(value is not None for value in values)
                else None
            )

        def peak(key, diagnostics=diagnostics):
            values = [d.get(key) for d in diagnostics]
            return max(values) if all(value is not None for value in values) else None

        acquire_count = total("pool_acquisitions")
        waits = [d.get("postgres_active_wait_estimated_millis") for d in diagnostics]
        wait_events = None
        if all(events is not None for events in waits):
            wait_events = {}
            for events in waits:
                for key, value in events.items():
                    wait_events[key] = wait_events.get(key, 0) + value
        cases.append(
            {
                **summary,
                "instances": result["metadata"]["server"]["instances"],
                "complete": all(d.get("complete") for d in diagnostics)
                and not any(t.get("observation_errors") for t in trials),
                "client_cpu": sum(t.get("client_cpu_seconds", 0) for t in trials)
                / summary["elapsed_seconds"]
                if all("client_cpu_seconds" in t for t in trials)
                else None,
                "server_cpu": weighted("server_cpu_cores"),
                "database_cpu": weighted("postgres_cpu_cores_observed"),
                "database_cpu_seconds": total("postgres_cpu_seconds_observed"),
                "pool_mean_ms": sum(
                    d["pool_acquire_mean_ms"] * d["pool_acquisitions"]
                    for d in diagnostics
                )
                / acquire_count
                if acquire_count
                and all(d.get("pool_acquire_mean_ms") is not None for d in diagnostics)
                else None,
                "pool_wait_peak": peak("pool_waiting_peak"),
                "pool_timeouts": total("pool_timeouts"),
                "blocked_peak": peak("blocked_backends_peak"),
                "database_read_ms": total("database_read_millis"),
                "database_write_ms": total("database_write_millis"),
                "wal_write_ms": total("wal_write_millis"),
                "wal_sync_ms": total("wal_sync_millis"),
                "wal_bytes": total("wal_bytes"),
                "gc_ms": total("gc_millis"),
                "wait_events": wait_events,
                "connection_errors": sum(
                    sum(t.get("connection_errors", {}).values()) for t in trials
                ),
                "warmup_errors": sum(
                    sum(t.get("warmup_errors", {}).values())
                    + sum(t.get("warmup_connection_errors", {}).values())
                    for t in trials
                ),
                "slow_1s": sum(
                    value >= 1000 for trial in trials for value in trial["latencies_ms"]
                ),
            }
        )
    return cases


def findings(case: dict, lower: list[dict]) -> tuple[str, str, str]:
    """依据可复核的排队/长尾/吞吐变化标记现象；推测不冒充 SQL 级归因。"""
    evidence, causes = [], []
    contention = False
    if not case["complete"]:
        return (
            "诊断不完整",
            "存在缺失采样，不能完整归因",
            "保留请求结果，诊断按未采集处理",
        )
    if case.get("connection_errors"):
        evidence.append(f"连接/收尾错误 {case['connection_errors']} 次")
        causes.append("WS 连接或末尾投递确认失败，需结合原始连接错误和未确认消息数排查")
    if case.get("warmup_errors"):
        evidence.append(f"预热错误 {case['warmup_errors']} 次")
    if case["failed"]:
        evidence.append(f"请求失败 {case['failed']} 次")
    if (case["pool_wait_peak"] or 0) > 0 or (case["pool_timeouts"] or 0) > 0:
        contention = True
        evidence.append(
            f"池等待峰值 {case['pool_wait_peak']}，获取均值 {display(case['pool_mean_ms'], 4)} ms，超时 {case['pool_timeouts']}"
        )
        causes.append("业务连接出现排队；SQL/事务持有连接期间的等待也可能向池传播")
    lock_ms = sum(
        value
        for event, value in (case["wait_events"] or {}).items()
        if event.startswith("Lock/")
    )
    if lock_ms > 0 or (case["blocked_peak"] or 0) > 0:
        contention = True
        evidence.append(
            f"Lock 估算 {lock_ms:.0f} 后端 ms，阻塞峰值 {case['blocked_peak']}"
        )
        if case["scenario"] in ("write_hot_user", "write_many_producers_hot_user"):
            causes.append(
                "单接收者顺序/游标行串行化是候选；单生产者场景还叠加配额行竞争，尚无锁对象证据"
            )
        elif case["scenario"] in ("write_many_users", "websocket_delivery"):
            causes.append(
                "同一生产者配额行与共享写入路径是候选；未采集锁对象，不能指定唯一竞争点"
            )
        else:
            causes.append(
                "数据库事务锁或共享内部结构存在争用；缺少锁对象及逐 SQL 数据定位"
            )
    internal_ms = sum(
        value
        for event, value in (case["wait_events"] or {}).items()
        if event.startswith("LWLock/")
    )
    if internal_ms > 0:
        contention = True
        evidence.append(f"LWLock 估算 {internal_ms:.0f} 后端 ms")
        causes.append(
            "PostgreSQL 内部共享结构存在等待；不能仅凭 LWLock 将其归因于生产者配额或接收者行锁"
        )
    if lower:
        best = max(lower, key=lambda item: item["successful_rps"])
        if (
            case["successful_rps"] < best["successful_rps"] * 0.9
            and case["p99_ms"] is not None
            and best["p99_ms"] is not None
            and case["p99_ms"] > best["p99_ms"] * 1.5
        ):
            contention = True
            evidence.append(
                f"相对 {best['concurrency']} 并发吞吐下降 {(1 - case['successful_rps'] / best['successful_rps']):.0%}，p99 上升"
            )
            causes.append(
                "本机闭环负载出现吞吐回落和排队放大；需结合本行 CPU/池/数据库等待区分来源"
            )
    if case["client_cpu"] is not None and case["client_cpu"] >= 0.85:
        evidence.append(f"发压端平均 {case['client_cpu']:.2f} 核")
        causes.append(
            "单进程发压接近一核，可能限制供给；需独立发压主机对照，当前不能排除"
        )
    if case["slow_1s"]:
        evidence.append(f"≥1 秒成功请求 {case['slow_1s']} 个")
        causes.append("稀疏长尾缺少逐请求时间戳，无法与 GC、WAL、调度等待对齐归因")
    io_ms = sum(
        value
        for event, value in (case["wait_events"] or {}).items()
        if event.startswith("IO/")
    )
    if io_ms:
        evidence.append(f"IO 等待估算 {io_ms:.0f} 后端 ms")
        causes.append("存在 I/O 等待采样；未采集 OS 磁盘利用率，不能据此判为磁盘饱和")
    if not evidence:
        evidence.append("未采到池排队、锁阻塞或秒级成功长尾")
    status = (
        "出现请求错误"
        if case["failed"] or case.get("connection_errors") or case.get("warmup_errors")
        else (
            "已观测争用或吞吐退化"
            if contention
            else ("存在长尾，瓶颈未定位" if case["slow_1s"] else "当前负载未定位瓶颈")
        )
    )
    return (
        status,
        "；".join(evidence),
        "；".join(dict.fromkeys(causes))
        or "未观测到不代表无瓶颈；当前闭环并发未证明容量上限",
    )


def render_trial_report(result: dict, source: str) -> str:
    """生成逐轮完整指标及独立结论，以同轮较低并发作为退化参照。"""
    lines = [
        render(result, source),
        "",
        "## 每轮性能结论",
        "",
        "| 并发 | 轮次 | 结论 | 观测证据 | 推测原因与边界 |",
        "| ---: | ---: | --- | --- | --- |",
    ]
    previous = {}
    for trial in sorted(
        result["trials"], key=lambda t: (t["repeat"], t["concurrency"])
    ):
        case = case_metrics({**result, "trials": [trial]})[0]
        lower = previous.setdefault(trial["repeat"], [])
        status, evidence, causes = findings(case, lower)
        lines.append(
            f"| {trial['concurrency']} | {trial['repeat']} | {status} | {evidence} | {causes} |"
        )
        lower.append(case)
    return "\n".join(lines) + "\n"


def host_quality(result, host_samples):
    """按实际测量窗口关联同机主机采样；未监测与采到零干扰分别记录。"""
    points = [
        (datetime.fromisoformat(sample["timestamp_utc"]).timestamp() * 1000, sample)
        for sample in host_samples
    ]
    monitored, matched = 0, []
    for trial in result["trials"]:
        observations = trial.get("observations", [])
        if len(observations) < 2:
            continue
        first, last = observations[0]["timestamp_ms"], observations[-1]["timestamp_ms"]
        window = [sample for timestamp, sample in points if first <= timestamp <= last]
        monitored += len(window) >= 2
        matched.extend(window)
    external = sum(
        any(not process["benchmark_worktree"] for process in sample["high_cpu_java"])
        for sample in matched
    )
    return {
        "monitored_trials": monitored,
        "total_trials": len(result["trials"]),
        "external_samples": external if matched else None,
        "max_load_1m": max(
            (sample["load_average"][0] for sample in matched), default=None
        ),
    }


def probe_tables(index, index_path, readme):
    """汇总发压进程对照；只对实际测量场景解释单进程供给限制。"""
    lines = []
    for entry in index.get("probes", []):
        result = read_result(index_path.parent / entry["result"])
        metadata = result["metadata"]
        if not metadata.get("server_source_tree") or metadata[
            "server_source_tree"
        ] != metadata.get("baseline_server_source_tree"):
            raise ValueError("probe server source tree mismatch or missing provenance")
        groups = {
            count: [
                trial
                for trial in result["trials"]
                if trial["load_generator_processes"] == count
            ]
            for count in [1, 4]
        }
        if any(
            {trial["repeat"] for trial in group} != {1, 2, 3} or len(group) != 3
            for group in groups.values()
        ):
            raise ValueError(
                "probe requires exactly three rounds for each generator count"
            )
        cases = {
            count: case_metrics({**result, "trials": group})[0]
            for count, group in groups.items()
        }
        scenario = entry["scenario"]
        lines += [
            "",
            f"### 发压端诊断：{NAMES[scenario]}",
            "",
            "保持单服务 JVM、总并发 32、业务池 8、调度 CPU 4、堆 2048 MiB；比较一个发压进程（32 worker）与四个发压进程（各 8 worker）。各进程先预热并通过屏障统一开始，每组 60 秒、三轮交替顺序。增加的是发压资源，服务预算不变。数据库由父进程统一采样，没有将四份重叠计数器相加。",
            "",
            "| 发压进程 | 成功/尝试 | 成功 req/s（轮次范围） | p50 / p95 / p99 / 最大 ms | 错误率 |",
            "| ---: | ---: | --- | --- | ---: |",
        ]
        for count, case in cases.items():
            latency = " / ".join(
                display(case[key]) for key in ["p50_ms", "p95_ms", "p99_ms", "max_ms"]
            )
            lines.append(
                f"| {count} | {case['succeeded']}/{case['attempted']} | {case['successful_rps']:.1f}（{case['rps_min']:.1f}–{case['rps_max']:.1f}） | {latency} | {case['error_rate']:.2%} |"
            )
        lines += [
            "",
            "| 发压进程 | 发压 / 服务 / DB 平均核数 | 获取均值 ms | 池等待 / 阻塞峰值 | WAL 写 ms | 诊断完整 |",
            "| ---: | --- | ---: | --- | ---: | --- |",
        ]
        for count, case in cases.items():
            cpu = " / ".join(
                display(case[key], 3)
                for key in ["client_cpu", "server_cpu", "database_cpu"]
            )
            lines.append(
                f"| {count} | {cpu} | {display(case['pool_mean_ms'], 6)} | {case['pool_wait_peak']} / {case['blocked_peak']} | {display(case['wal_write_ms'])} | {'是' if case['complete'] else '否'} |"
            )
        one, four = cases[1], cases[4]
        ratio = (
            four["successful_rps"] / one["successful_rps"]
            if one["successful_rps"]
            else None
        )
        distinct = four["rps_min"] > one["rps_max"]
        if (
            ratio is not None
            and ratio > 1.25
            and distinct
            and all(case["complete"] and not case["failed"] for case in cases.values())
        ):
            conclusion = f"四进程吞吐为单进程的 {ratio:.2f} 倍，三轮吞吐范围不重叠；本次对照确认单进程发压方式限制了该项测得吞吐。不能将原矩阵的高并发吞吐回落直接当作服务容量上限。Python 调度、HTTP 连接池等具体开销仍需逐函数剖析，其他场景和多实例不能仅凭此对照归因。"
        else:
            conclusion = f"四/单进程吞吐比为 {display(ratio)}，本次对照不足以确认稳定的发压端改善；结合错误、波动与资源指标解读，不指定唯一根因。"
        raw = os.path.relpath(index_path.parent / entry["result"], readme.parent)
        tree = result["metadata"].get("server_source_tree", "未记录")
        lines += [
            "",
            conclusion,
            "",
            f"工具提交 `{result['metadata']['git_revision']}`；服务源码树 `{tree}` 与对应基线核验一致。[完整原始结果、每个发压子进程及统一观测]({raw})。父进程统计的 CPU 不包含四个子进程以外的其他发压工具；这里的发压 CPU 为各测量子进程的累计时间之和。",
        ]
    return lines


def render_matrix(index_path: Path, readme: Path) -> str:
    """生成全部已核验测试单元的对照、诊断与逐项结论，缺失覆盖时拒绝发布。"""
    import json

    index = json.loads(index_path.read_text())
    host_samples = []
    if index.get("host_observations"):
        host_samples = [
            json.loads(line)
            for line in (index_path.parent / index["host_observations"])
            .read_text()
            .splitlines()
        ]
    expected = index["expected"]
    runs, cases, observed = [], [], set()
    for run in index["runs"]:
        path = index_path.parent / run["result"]
        result = read_result(path)
        quality = host_quality(result, host_samples)
        if quality["external_samples"]:
            raise ValueError(
                f"external host interference inside measurement windows: {run['result']}"
            )
        runs.append((run, result))
        for trial in result["trials"]:
            key = (
                result["metadata"]["server"]["instances"],
                trial["scenario"],
                trial["concurrency"],
                trial["repeat"],
            )
            if key in observed:
                raise ValueError(f"duplicate matrix trial: {key}")
            observed.add(key)
        cases.extend(case_metrics(result))
    required = {
        (n, s, c, r)
        for n in expected["instances"]
        for s in expected["scenarios"]
        for c in expected["concurrency"]
        for r in range(1, expected["repeats"] + 1)
    }
    if observed != required:
        raise ValueError(
            f"matrix coverage mismatch: missing={sorted(required - observed)}, extra={sorted(observed - required)}"
        )
    settings = [
        (
            result["metadata"]["duration_seconds"],
            result["metadata"]["warmup_seconds"],
            result["metadata"]["active_users"],
            result["metadata"]["producer_identities"],
            result["metadata"]["server"]["database_connections_total"],
            result["metadata"]["server"]["server_cpus_total"],
            result["metadata"]["server"]["server_heap_mib_total"],
            index.get("server_source_trees", {}).get(
                result["metadata"]["git_revision"], result["metadata"]["git_revision"]
            ),
        )
        for _, result in runs
    ]
    if len(set(settings)) != 1:
        raise ValueError("matrix resource/workload/revision mismatch")
    cases.sort(
        key=lambda case: (
            list(NAMES).index(case["scenario"]),
            case["concurrency"],
            case["instances"],
        )
    )
    by_key = {
        (case["scenario"], case["concurrency"], case["instances"]): case
        for case in cases
    }
    duration, warmup, users, producers, connections, cpus, heap, _source = settings[0]
    revisions = sorted({r["metadata"]["git_revision"] for _, r in runs})
    revision = runs[0][1]["metadata"]["git_revision"]
    reference = runs[0][1]["metadata"]
    total_attempted = sum(case["attempted"] for case in cases)
    failures = sum(case["failed"] for case in cases)
    connection_errors = sum(case["connection_errors"] for case in cases)
    warmup_errors = sum(case["warmup_errors"] for case in cases)
    lines = [
        "## 实测结果",
        "",
        f"本次完成 {len(runs)} 个独立夹具运行、{len(cases)} 个场景/并发/拓扑测试单元、{len(observed)} 轮测量，合计 {total_attempted:,} 次请求/消息，失败 {failures:,} 次；连接/收尾错误 {connection_errors} 次，预热错误 {warmup_errors} 次（独立口径，不能与消息失败相加）。每个单元的指标合并原始成功延迟样本，未平均各轮 p99。",
        "",
        "### 环境、方法与覆盖",
        "",
        "| 项目 | 实际测量条件 |",
        "| --- | --- |",
        f"| 测量时间 | {index['started_utc']} 至 {index['finished_utc']} |",
        f"| 源码 | 服务基线 `{revision}`；各运行工具提交：{', '.join(revisions)}。服务源码树一致性见索引；未重写原始元数据 |",
        f"| 主机 | {reference['cpu_model']}；逻辑 CPU {reference['logical_cpus']}；内存 {reference['memory_bytes'] / 2**30:.0f} GiB；{reference['os']} |",
        f"| Java / Python / PostgreSQL | {reference['server']['java']} / {reference['python']} / {reference['server']['postgres']['version']} |",
        f"| 资源总预算 | 业务连接 {connections}；JVM 调度 CPU {cpus}；堆 {heap} MiB。单实例为 8/4/2048；双实例各为 4/2/1024 |",
        f"| 负载 | {users} 接收者、{producers} 个可选生产者身份；单生产者场景固定一个身份；单接收者场景固定一个账户 |",
        f"| 时间与并发 | 每轮 {duration:g} 秒，HTTP 预热 {warmup:g} 秒；重复 {expected['repeats']} 轮；并发 {expected['concurrency']} |",
        "| 持久化 | fsync、synchronous_commit、full_page_writes、track_io_timing、track_wal_io_timing 均开启；WAL 同步方式见逐项环境 |",
        "| 数据准备 | 每个场景/拓扑新建临时 PostgreSQL，256 用户各预置 20 条通知；同一夹具内三轮与四档并发持续积累数据。单/双实例执行顺序按场景交替 |",
        "| 发压与部署 | 独立服务 JVM；发压端、服务、数据库仍共享本机。单进程 Python HTTPX 闭环发压，不重试；各服务共用主库 |",
        "| 推送 / WS | 每条通知写 Outbox，APNs Worker 关闭。WS 使用单生产者；双实例 POST 与 WS 落在不同节点 |",
        "",
        "本机不是专用隔离服务器，其他桌面进程、磁盘与温度变化可能影响结果。ActiveProcessorCount 是调度提示，不是 OS CPU 配额。这里比较的是同主机相同服务总预算的单/双 JVM 行为，不能外推跨主机扩容收益或生产容量。",
        "",
        "### 单实例与双实例吞吐及尾延迟",
        "",
        "| 场景 | 并发 | 单实例 req/s | 双实例 req/s | 双/单吞吐 | 单实例 p99 ms | 双实例 p99 ms | 对照结论 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for scenario in expected["scenarios"]:
        for concurrency in expected["concurrency"]:
            a, b = (
                by_key[(scenario, concurrency, 1)],
                by_key[(scenario, concurrency, 2)],
            )
            ratio = (
                b["successful_rps"] / a["successful_rps"]
                if a["successful_rps"]
                else None
            )
            overlap = not (a["rps_max"] < b["rps_min"] or b["rps_max"] < a["rps_min"])
            conclusion = (
                "重复轮次范围重叠，未见稳定扩容收益"
                if overlap
                else ("双实例吞吐较高" if ratio and ratio > 1 else "双实例吞吐较低")
            )
            if a["failed"] or b["failed"]:
                conclusion += "；存在失败，结合错误率解读"
            lines.append(
                f"| {NAMES[scenario]} | {concurrency} | {a['successful_rps']:.1f} | {b['successful_rps']:.1f} | {display(ratio)} | {display(a['p99_ms'])} | {display(b['p99_ms'])} | {conclusion} |"
            )
    lines += [
        "",
        "WS 的 req/s 表示完整轮次消息/s，含连接建立与尾部排空。吞吐区间为三轮最小/最大值；区间重叠只表示波动影响判断，不是统计显著性检验。",
        "",
        "### 每个测试单元的性能结论与瓶颈推测",
        "",
        "| 场景 | 实例 | 并发 | 成功 req/s（轮次范围） | 错误率 | p99 / 最大 ms | 是否定位瓶颈 | 观测证据 | 可能原因与限制 |",
        "| --- | ---: | ---: | --- | ---: | --- | --- | --- | --- |",
    ]
    for case in cases:
        lower = [
            row
            for row in cases
            if row["scenario"] == case["scenario"]
            and row["instances"] == case["instances"]
            and row["concurrency"] < case["concurrency"]
        ]
        status, evidence, causes = findings(case, lower)
        lines.append(
            f"| {NAMES[case['scenario']]} | {case['instances']} | {case['concurrency']} | {case['successful_rps']:.1f}（{case['rps_min']:.1f}–{case['rps_max']:.1f}） | {case['error_rate']:.2%} | {display(case['p99_ms'])} / {display(case['max_ms'])} | {status} | {evidence} | {causes} |"
        )
    lines += [
        "",
        "判断口径：池/锁正采样证明出现等待，不证明它是主瓶颈。相对较低并发的最佳吞吐下降超过 10% 且 p99 增至 1.5 倍以上，标记吞吐退化现象；发压 CPU ≥0.85 核仅列为单进程供给限制候选。这些是描述性规则，没有业务 SLO，不能判定业务是否达标。SQL 级原因属于推测。",
        "",
        "### 各测试单元 CPU、连接池与 GC",
        "",
        "| 场景 | 实例 | 并发 | 发压 / 服务 / DB 平均核数 | DB CPU 秒 | 获取均值 ms | 池等待峰值 / 获取超时 | 锁阻塞峰值 | GC 累计 ms |",
        "| --- | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: |",
    ]
    for case in cases:
        cpu = " / ".join(
            display(case[key], 3)
            for key in ["client_cpu", "server_cpu", "database_cpu"]
        )
        lines.append(
            f"| {NAMES[case['scenario']]} | {case['instances']} | {case['concurrency']} | {cpu} | {display(case['database_cpu_seconds'])} | {display(case['pool_mean_ms'], 6)} | {case['pool_wait_peak']} / {case['pool_timeouts']} | {case['blocked_peak']} | {display(case['gc_ms'])} |"
        )
    lines += [
        "",
        "CPU 按采样墙钟时长加权，峰值取各轮最大；GC 耗时合计所有服务节点。PostgreSQL 只统计相邻采样共同可见 PID，可能漏掉进程退出尾段；CPU 秒不表示调度等待。",
        "",
        "### 数据库 WAL 与等待事件",
        "",
        "| 场景 | 实例 | 并发 | 数据块读 / 写 ms | WAL MiB | WAL 写 / 同步 ms | active 等待估算（后端 ms，三轮合计） |",
        "| --- | ---: | ---: | --- | ---: | --- | --- |",
    ]
    for case in cases:
        waits = case["wait_events"]
        event_text = (
            "未采集"
            if waits is None
            else (
                "；".join(f"{key}: {value:.0f}" for key, value in sorted(waits.items()))
                or "0（未命中）"
            )
        )
        wal_mib = case["wal_bytes"] / 2**20 if case["wal_bytes"] is not None else None
        lines.append(
            f"| {NAMES[case['scenario']]} | {case['instances']} | {case['concurrency']} | {display(case['database_read_ms'])} / {display(case['database_write_ms'])} | {display(wal_mib)} | {display(case['wal_write_ms'])} / {display(case['wal_sync_ms'])} | {event_text} |"
        )
    lines += [
        "",
        "等待是对 active 后端数量作梯形积分的采样估算，排除 idle，可漏掉短等待；多个后端的等待可重叠。WAL 写与同步分列，open_datasync 的同步成本可能计入写耗时。CPU、I/O 与等待不能相加得到请求延迟。",
        "",
        "### 逐轮与逐节点完整记录",
        "",
        "| 场景 | 实例 | 轮次 | 请求失败 | 诊断完整 | 主机采样覆盖轮次 | 进程退出码 | 完整报告 | 原始快照与全部延迟 |",
        "| --- | ---: | ---: | ---: | --- | --- | ---: | --- | --- |",
    ]
    for run, result in runs:
        quality = host_quality(result, host_samples)
        host_text = (
            f"{quality['monitored_trials']}/{quality['total_trials']}；外部 Java 高 CPU 采样 {quality['external_samples']}"
            if quality["external_samples"] is not None
            else "未持续监测"
        )
        raw = os.path.relpath(index_path.parent / run["result"], readme.parent)
        report = os.path.relpath(index_path.parent / run["report"], readme.parent)
        all_complete = all(
            summarize_observations(trial.get("observations", [])).get("complete")
            and not trial.get("observation_errors")
            for trial in result["trials"]
        )
        lines.append(
            f"| {NAMES[run['scenario']]} | {run['instances']} | {len(result['trials'])} | {sum(t['failed'] for t in result['trials'])} | {'是' if all_complete else '否'} | {host_text} | {run.get('exit_code', '未记录')} | [逐轮、节点、池、事务、缓存、I/O 与采样质量]({report}) | [JSON.gz]({raw}) |"
        )
    lines += [
        "",
        "WS 旧工具在单实例第二轮末尾收齐超时后提前退出，该尝试单列保留，不当作零错误；修正工具保存失败计数后，WS 使用全新夹具完整重测。服务源码树与 HTTP 基线一致。主机观测在发现并行构建干扰后加入，早期写入组未持续监测；有观测的实际测量窗若采到外部高 CPU Java，生成器拒绝将其纳入正式矩阵。主机采样只是线索，可能漏掉短峰值或其他类型进程，不能证明绝对隔离。退出码与请求/采样质量分开：具体非零原因见索引记录，退出清理异常不等同于请求失败。",
        "",
        "各独立报告保留逐轮 p50/p95/p99/max、错误分类与长尾数量、采样间隔与错误、每节点 CPU/堆/GC/池、数据库事务/缓存/读写/WAL 计数器。原始文件可用 gzip 解压读取；未修改原始测量时间、版本与工作区状态。",
        "",
    ]
    lines += probe_tables(index, index_path, readme)
    lines += [
        "### 源码核对与长尾口径",
        "",
        "HTTPcore 1.0.9 在请求入池或移出时扫描连接、空闲连接与排队请求。较大连接池的管理开销是候选原因，未逐函数剖析确认。[固定版本连接池源码](https://github.com/encode/httpcore/blob/1.0.9/httpcore/_async/connection_pool.py)。",
        "",
        "本基准 HTTPX timeout=30 分别约束连接、读取、写入和取连接等阶段，并非整请求总时限；成功请求的总耗时可以超过 30 秒，不能把这类长尾改记为零或丢弃。[HTTPX 超时说明](https://www.python-httpx.org/advanced/timeouts/)。",
        "",
        f"服务端对同一生产者的配额记录执行原子更新，对接收者的顺序/游标状态使用行锁；它们是写入争用的候选路径。读取请求也经过全局限流，仍有配额更新和 WAL。源码约束不等于本次锁对象已定位。[测量版本 PostgresStore](https://github.com/bitlap/polyglot-im/blob/{revision}/scala-server/src/main/scala/polyglot/im/server/infrastructure/postgres/PostgresStore.scala)。",
        "",
        "### 仍无法由本次结果确定的事项",
        "",
        "未采集逐 SQL 执行计划、锁对象、OS 磁盘队列/利用率、CPU 调度等待、逐请求时间戳或 JFR 火焰图，因此不能把某个最大延迟精确归因到一次 GC 或数据库等待。未进行跨主机网络、弱网、APNs、文字单聊、固定到达率或长期稳定性测试；没有目标 SLO，不将某档并发的吞吐当作生产容量。",
        "",
    ]
    return "\n".join(lines)


def main():
    """从矩阵索引生成 README；保留使用说明，并要求全部预期轮次实际存在。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("index", type=Path)
    parser.add_argument("--readme", type=Path, default=Path("benchmark/README.md"))
    parser.add_argument(
        "--details", action="store_true", help="同时重新生成各运行的逐轮完整报告"
    )
    args = parser.parse_args()
    if args.details:
        import json

        index = json.loads(args.index.read_text())
        # 先验证完整矩阵，避免缺失数据时写出部分详情。
        render_matrix(args.index, args.readme)
        for entry in index["runs"]:
            result = read_result(args.index.parent / entry["result"])
            report = args.index.parent / entry["report"]
            report.write_text(
                f"# {NAMES[entry['scenario']]}：{entry['instances']} 实例\n\n## 实测结果\n\n"
                + render_trial_report(result, entry["result"])
            )
        for entry in index.get("probes", []):
            report = args.index.parent / entry["report"]
            report.write_text(
                "# 发压进程数对照\n\n"
                + "\n".join(probe_tables({"probes": [entry]}, args.index, report))
                + "\n"
            )
    section = render_matrix(args.index, args.readme)
    prefix = args.readme.read_text().split("## 实测结果")[0]
    args.readme.write_text(prefix + section)


if __name__ == "__main__":
    main()
