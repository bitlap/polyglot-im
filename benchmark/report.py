"""从原始样本生成 README 实测表；不平均各轮分位数。"""

import argparse
import gzip
import json
import os
from itertools import pairwise
from pathlib import Path

from .metrics import aggregate_trials, summarize
from .telemetry import counter_delta, summarize_observations

NAMES = {
    "write_many_users": "单生产者 → 多接收者",
    "write_hot_user": "单生产者 → 单接收者",
    "write_many_producers_many_users": "多生产者 → 多接收者",
    "write_many_producers_hot_user": "多生产者 → 单接收者",
    "history": "历史分页（20 条）",
    "sync_empty": "空增量同步",
    "websocket_delivery": "WS 端到端投递",
}


def read_result(path: Path) -> dict:
    """读取普通或 gzip 压缩的原始结果，不改变保存的测量元数据。"""
    payload = path.read_bytes()
    return json.loads(gzip.decompress(payload) if path.suffix == ".gz" else payload)


def display(value, digits=2):
    """格式化可缺失的延迟指标，全部请求失败时不把缺失分位数记为零。"""
    return "未采集" if value is None else f"{value:.{digits}f}"


def detailed_tables(result: dict) -> list[str]:
    """展开测量覆盖、逐轮错误与长尾、采样质量、节点资源和数据库计数器。"""
    trials = result["trials"]
    lines = ["", "### 测量覆盖", "", "| 场景 | 并发与实际轮数 |", "| --- | --- |"]
    for scenario, label in NAMES.items():
        group = [row for row in aggregate_trials(trials) if row["scenario"] == scenario]
        coverage = "；".join(
            f"{row['concurrency']} 并发 × {row['trials']} 轮" for row in group
        )
        lines.append(f"| {label} | {coverage or '未测试'} |")
    lines += [
        "",
        "只列出本文件实际完成的测量；未测试场景没有零延迟或零错误结论。",
        "",
        "### 逐轮吞吐、长尾与错误",
        "",
        "| 场景 | 并发 | 轮次 | 实测秒数 | 成功/尝试 | 成功 req/s | p99 ms | 最大 ms | ≥100 ms / ≥1 s | 请求错误 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for trial in trials:
        stats = summarize(
            trial["latencies_ms"], trial["attempted"], trial["elapsed_seconds"]
        )
        errors = trial.get("errors")
        error_text = (
            "未记录"
            if errors is None
            else (json.dumps(errors, ensure_ascii=False) if errors else "无")
        )
        lines.append(
            f"| {NAMES[trial['scenario']]} | {trial['concurrency']} | {trial['repeat']} | {stats['elapsed_seconds']:.3f} | "
            f"{stats['succeeded']}/{stats['attempted']} | {stats['successful_rps']:.2f} | {display(stats['p99_ms'])} | {display(stats['max_ms'])} | "
            f"{sum(value >= 100 for value in trial['latencies_ms'])} / {sum(value >= 1000 for value in trial['latencies_ms'])} | {error_text} |"
        )
    lines += [
        "",
        "长尾数量只统计成功请求；失败请求单独计入错误，不参与成功延迟分位数。WS 的 req/s 列表示完整轮次消息/s。",
        "",
        "### 采样质量",
        "",
        "| 场景 | 并发 | 轮次 | 快照数 | 覆盖秒数 | 最大采样间隔 ms | 观测错误 | PostgreSQL CPU 来源 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for trial in trials:
        samples = trial.get("observations", [])
        gaps = [
            after["timestamp_ms"] - before["timestamp_ms"]
            for before, after in pairwise(samples)
        ]
        elapsed = (
            (samples[-1]["timestamp_ms"] - samples[0]["timestamp_ms"]) / 1000
            if len(samples) >= 2
            else None
        )
        sources = sorted(
            {
                sample["postgres"].get("process_cpu_source", "未记录")
                for sample in samples
            }
        )
        errors = trial.get("observation_errors")
        lines.append(
            f"| {NAMES[trial['scenario']]} | {trial['concurrency']} | {trial['repeat']} | {len(samples) if 'observations' in trial else '未采集'} | "
            f"{display(elapsed, 3)} | {max(gaps) if gaps else '未采集'} | {len(errors) if errors is not None else '未记录'} | {', '.join(sources) or '未采集'} |"
        )
    lines += [
        "",
        "### 各服务节点 CPU、堆与 GC",
        "",
        "| 场景 | 并发 | 轮次 | 节点 / PID | CPU 秒 | 平均核数 | 堆起/末/采样峰值 MiB | GC 次数差 | GC ms |",
        "| --- | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: |",
    ]
    pool_lines = [
        "",
        "### 各节点连接池",
        "",
        "| 场景 | 并发 | 轮次 | 节点 / PID | 池上限 | 活跃/等待采样峰值 | 获取次数差 | 获取均值 ms | 获取超时差 | 启动以来获取最大 ms |",
        "| --- | ---: | ---: | --- | ---: | --- | ---: | ---: | ---: | ---: |",
    ]
    for trial in trials:
        samples = trial.get("observations", [])
        if len(samples) < 2:
            continue
        elapsed = (samples[-1]["timestamp_ms"] - samples[0]["timestamp_ms"]) / 1000
        for before in samples[0]["nodes"]:
            nodes = [
                node
                for sample in samples
                for node in sample["nodes"]
                if node["pid"] == before["pid"]
            ]
            if len(nodes) != len(samples) or elapsed <= 0:
                continue
            after = nodes[-1]
            prefix = f"| {NAMES[trial['scenario']]} | {trial['concurrency']} | {trial['repeat']} | {before['node']} / {before['pid']} |"
            cpu = (after["process_cpu_nanos"] - before["process_cpu_nanos"]) / 1e9
            heaps = [
                before["heap_used_bytes"],
                after["heap_used_bytes"],
                max(node["heap_used_bytes"] for node in nodes),
            ]
            lines.append(
                prefix
                + f" {cpu:.3f} | {cpu / elapsed:.3f} | "
                + "/".join(f"{heap / 2**20:.2f}" for heap in heaps)
                + f" | {after['gc_count'] - before['gc_count']} | {after['gc_millis'] - before['gc_millis']} |"
            )
            start, end = before["pool"], after["pool"]
            count = end["acquisitions"] - start["acquisitions"]
            mean = (
                (end["acquire_nanos"] - start["acquire_nanos"]) / count / 1e6
                if count
                else None
            )
            pool_lines.append(
                prefix
                + f" {end['maximum']} | {max(node['pool']['active'] for node in nodes)} / {max(node['pool']['waiting'] for node in nodes)} | "
                f"{count} | {display(mean, 6)} | {end['timeouts'] - start['timeouts']} | {end['max_acquire_nanos_since_start'] / 1e6:.3f} |"
            )
    if not any(len(trial.get("observations", [])) >= 2 for trial in trials):
        lines += ["", "未采集节点快照。"]
        pool_lines += ["", "未采集节点快照。"]
    lines += pool_lines + [
        "",
        "节点缺失或 PID 变化时不计算该节点差值；堆峰值与池峰值为离散采样峰值。连接获取最大值包含预热与先前轮次，不是本轮最大值。",
        "",
        "### 数据库事务、缓存与 WAL",
        "",
        "| 场景 | 并发 | 轮次 | commit / rollback | 缓存命中 / 读取块 | 缓存命中率 | 临时写字节 | 死锁 | WAL records / FPI | WAL 写 / 同步次数 | WAL buffer 满次数 |",
        "| --- | ---: | ---: | --- | --- | ---: | ---: | ---: | --- | --- | ---: |",
    ]
    for trial in trials:
        samples = trial.get("observations", [])

        def delta(group, key, samples=samples):
            return counter_delta(samples, group, key) if len(samples) >= 2 else None

        def integer(group, key):
            value = delta(group, key)
            return "未采集" if value is None else str(int(value))

        hit, read = delta("database", "blks_hit"), delta("database", "blks_read")
        rate = (
            f"{hit / (hit + read):.3%}"
            if hit is not None and read is not None and hit + read
            else "未采集"
        )
        lines.append(
            f"| {NAMES[trial['scenario']]} | {trial['concurrency']} | {trial['repeat']} | "
            f"{integer('database', 'xact_commit')} / {integer('database', 'xact_rollback')} | {integer('database', 'blks_hit')} / {integer('database', 'blks_read')} | {rate} | "
            f"{integer('database', 'temp_bytes')} | {integer('database', 'deadlocks')} | {integer('wal', 'wal_records')} / {integer('wal', 'wal_fpi')} | "
            f"{integer('wal', 'wal_write')} / {integer('wal', 'wal_sync')} | {integer('wal', 'wal_buffers_full')} |"
        )
    lines += [
        "",
        "数据库表为同一 stats_reset 周期内的累计差值，包含后台协调与观测查询，事务数不等于业务请求数；读取块可能命中 OS 页缓存，缓存命中率仅代表 PostgreSQL 缓冲区。统计刷新存在延迟。未采集 SQL 执行计划、逐语句耗时、OS 磁盘利用率/队列或 CPU 调度等待。原始延迟没有逐请求时间戳，不能将最大延迟与某次 GC、WAL 或等待事件建立时间关联。",
    ]
    return lines


def render(result: dict, source: str) -> str:
    """从原始样本生成中文指标表，区分历史拓扑、资源总预算与缺失观测。"""
    result = {
        **result,
        "trials": [
            {**trial, "diagnostics": summarize_observations(trial["observations"])}
            if len(trial.get("observations", [])) >= 2
            else trial
            for trial in result["trials"]
        ],
    }
    metadata = result["metadata"]
    server = metadata["server"]
    rows = aggregate_trials(result["trials"])
    realtime = (
        "PostgreSQL 提交后通知 / 5 秒协调补拉"
        if server.get("changefeed")
        else "历史 500ms 轮询"
    )
    if server.get("nodes"):
        budget = f"独立 JVM {len(server['nodes'])} 个；业务池总预算 {server['database_connections_total']}，各节点 {server['database_pool_per_node']}；可见 CPU 总数 {server['server_cpus_total']}，堆总上限 {server['server_heap_mib_total']} MiB"
    else:
        budget = f"同一 JVM 可见 CPU {server['jvm_processors']}，最大堆 {server['max_heap_bytes'] / 2**30:.1f} GiB；每实例连接池 {server['database_pool']}"
    measurement = (
        f"每 HTTP 轮至少 {metadata['duration_seconds']} 秒，预热 {metadata['warmup_seconds']} 秒"
        if metadata.get("duration_seconds")
        else f"每 HTTP 轮 {metadata['requests_per_trial']} 次；预热 {metadata['warmup_requests']} 次"
    )
    total_attempted = sum(row["attempted"] for row in rows)
    total_succeeded = sum(row["succeeded"] for row in rows)
    long_tail = sum(
        value >= 1000 for trial in result["trials"] for value in trial["latencies_ms"]
    )
    summary = (
        f"本文件共 {len(result['trials'])} 个测量轮次，覆盖 {len({trial['scenario'] for trial in result['trials']})}/{len(NAMES)} 种场景；"
        f"成功 {total_succeeded}/{total_attempted}，成功请求中 ≥1 秒的有 {long_tail} 个。"
        "单个结果文件只包含一种实例拓扑，不能独立给出单/双实例扩容结论。"
    )
    lines = [
        summary,
        f"测试时间：{metadata['timestamp_utc']}。源码提交 `{metadata['git_revision']}`，工作区未提交修改：`{metadata['working_tree_dirty']}`。",
        f"原始环境/每轮指标/逐条延迟样本见 [{Path(source).name}]({source})。服务夹具提交 `{server.get('fixture_revision', '历史未记录')}`，夹具工作区修改：`{server.get('fixture_working_tree_dirty', '历史未记录')}`。",
        "",
        "| 环境 | 本次参数 |",
        "| --- | --- |",
        f"| 操作系统 / 架构 | {metadata['os']} / {metadata['machine']} |",
        f"| CPU / 内存 | {metadata.get('cpu_model', '未采集')}；逻辑 CPU {metadata['logical_cpus']}；物理内存 {metadata.get('memory_bytes', 0) / 2**30:.1f} GiB |",
        f"| Java / 压测 Python | {server['java']} / {metadata['python']} |",
        f"| 实例 / 拓扑 / 实时 | {server.get('instances', 1)}；{server.get('topology', 'single-instance')}；{realtime} |",
        f"| JVM / 数据库连接池 | {budget} |",
        f"| PostgreSQL | {server['postgres']['version']} |",
        f"| 持久化 | fsync={server['postgres']['fsync']}，synchronous_commit={server['postgres']['synchronous_commit']}，full_page_writes={server['postgres']['full_page_writes']}，wal_sync_method={server['postgres']['wal_sync_method']} |",
        f"| 发压 / 预热 / 重复 | {metadata['load_generator']}；{measurement}；重复 {metadata['repeats']} 轮 |",
        f"| WS / 用户 / 生产者 / 限流 | 每连接 {metadata['ws_messages_per_connection']} 条；{metadata.get('active_users', 256)} 用户；生产者身份 {metadata.get('producer_identities', '历史未记录')}；限流阈值 {server['rate_limit_per_minute']}/分钟 |",
        f"| 采样 / I/O 计时 | 发压端间隔 {metadata.get('telemetry_interval_seconds', '未记录')} 秒；track_io_timing={server['postgres'].get('track_io_timing', '未记录')}；track_wal_io_timing={server['postgres'].get('track_wal_io_timing', '未记录')} |",
        f"| 推送事务负载 | APNs Worker={server.get('apns_enabled', '未记录')}；每消息 Outbox 任务 {server.get('outbox_jobs_per_message', '未记录')} |",
        "",
        "### HTTP 并发与延迟",
        "",
        "| 场景 | 并发请求 | 成功/尝试 | 错误率 | 成功 req/s | 各轮 req/s 范围 | p50 ms | p95 ms | p99 ms | 最大 ms |",
        "| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        if row["scenario"] == "websocket_delivery":
            continue
        lines.append(
            f"| {NAMES[row['scenario']]} | {row['concurrency']} | {row['succeeded']}/{row['attempted']} | {row['error_rate']:.2%} | {row['successful_rps']:.1f} | {row['rps_min']:.1f}–{row['rps_max']:.1f} | {display(row['p50_ms'])} | {display(row['p95_ms'])} | {display(row['p99_ms'])} | {display(row['max_ms'])} |"
        )
    lines += [
        "",
        "### WebSocket 并发连接与端到端延迟",
        "",
        "| 在线连接 | 收到/发送 | 错误率 | 完整轮次消息/s | p50 ms | p95 ms | p99 ms | 最大 ms |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        if row["scenario"] != "websocket_delivery":
            continue
        lines.append(
            f"| {row['concurrency']} | {row['succeeded']}/{row['attempted']} | {row['error_rate']:.2%} | {row['successful_rps']:.1f} | {display(row['p50_ms'])} | {display(row['p95_ms'])} | {display(row['p99_ms'])} | {display(row['max_ms'])} |"
        )
    if any("diagnostics" in row for row in result["trials"]):
        lines += [
            "",
            "### 每轮资源与等待观测",
            "",
            "| 场景 | 并发 | 轮次 | 完整采样 | 发压 CPU 核数 | 服务 CPU 核数 | PostgreSQL 观测核数 | 池获取均值 ms | 池等待峰值 | 锁阻塞峰值 | GC ms | WAL MiB |",
            "| --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ]
        for row in result["trials"]:
            diagnostic = row.get("diagnostics", {})

            def number(name, factor=1, digits=2, diagnostic=diagnostic):
                value = diagnostic.get(name)
                return "未采集" if value is None else f"{value / factor:.{digits}f}"

            complete = diagnostic.get("complete", False) and not row.get(
                "observation_errors"
            )
            lines.append(
                f"| {NAMES[row['scenario']]} | {row['concurrency']} | {row['repeat']} | {'是' if complete else '否'} | {display(row.get('client_cpu_seconds') / row['elapsed_seconds'] if row.get('client_cpu_seconds') is not None else None)} | {number('server_cpu_cores')} | {number('postgres_cpu_cores_observed')} | {number('pool_acquire_mean_ms', digits=6)} | {number('pool_waiting_peak')} | {number('blocked_backends_peak')} | {number('gc_millis')} | {number('wal_bytes', 2**20)} |"
            )
        lines += [
            "",
            "### 数据库 CPU 与耗时",
            "",
            "| 场景 | 并发 | 轮次 | CPU 秒（观测） | 数据块读 ms | 数据块写 ms | WAL 写 ms | WAL 同步 ms | active 等待估算（后端 ms） |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
        ]
        for row in result["trials"]:
            diagnostic = row.get("diagnostics", {})
            values = [
                display(diagnostic.get(name))
                for name in (
                    "postgres_cpu_seconds_observed",
                    "database_read_millis",
                    "database_write_millis",
                    "wal_write_millis",
                    "wal_sync_millis",
                )
            ]
            waits = diagnostic.get("postgres_active_wait_estimated_millis")
            wait_text = (
                "未采集"
                if waits is None
                else (
                    "；".join(
                        f"{event}: {value:.2f}"
                        for event, value in sorted(waits.items())
                    )
                    or "0.00（采样未命中）"
                )
            )
            lines.append(
                f"| {NAMES[row['scenario']]} | {row['concurrency']} | {row['repeat']} | "
                + " | ".join(values)
                + f" | {wait_text} |"
            )
        lines += [
            "",
            "数据库 CPU 秒是相邻快照共同可见 PostgreSQL 进程的累计 CPU 时间差之和；进程在两次采样间退出可能漏计。它不表示 CPU 调度等待时间。数据块 I/O 与 WAL 耗时来自 PostgreSQL 累计计数器差值；WAL 写和同步分列，open_datasync 下同步成本可能计入写耗时。各项是累计操作耗时，不能与 CPU 时间相加得到请求延迟。",
            "active 等待按 pg_stat_activity 的等待类型/事件分组，对后端数量作梯形积分，排除 idle 连接；单位为后端毫秒，多个后端可重叠，可能超过墙钟时长。该值为离散采样估算，短等待可能漏采，不能代表精确锁等待耗时；未命中不证明没有等待。连接池等待与数据库等待分别记录。",
        ]
        lines += [
            "",
            "CPU 核数为累计进程 CPU 时间差 / 采样墙钟时间，1 表示占用一个核；PostgreSQL 只统计采样时可见进程。锁等待是离散采样，短暂争用可能漏采，零采样不能证明无争用。连接获取均值包含借用开销；最大值是节点启动以来的累计峰值。原始快照保留 wait_event、池占用、GC、WAL 和 I/O 时间。",
        ]
    lines += detailed_tables(result)
    lines += [
        "",
        "### 瓶颈排查与证据边界",
        "",
        "| 方向 | 本报告可核对的证据 | 判断限制 |",
        "| --- | --- | --- |",
        "| 发压端 | 发压 CPU 核数、吞吐与并发变化 | 单进程 CPU 接近一核时需排除发压能力限制；同机发压与服务争用，不能直接判定服务容量 |",
        "| 服务 CPU 与 GC | 逐节点 CPU、堆峰值、GC 次数与耗时，检查节点是否倾斜 | 平均 CPU 未满不能排除单线程热点或短时饱和；累计 GC 不是逐请求暂停证据 |",
        "| 连接池 | 获取均值、活跃/等待峰值与获取超时差 | 持续等待和超时支持排队判断；低均值和零采样峰值不排除短暂排队，不能仅凭并发数加大池 |",
        "| 数据库锁 | 锁阻塞峰值与 Lock/LWLock 等待事件估算 | 正采样说明窗口内出现等待，仍需具体 SQL/锁对象定位；零采样不能证明无争用 |",
        "| 数据库 I/O | 数据块读写耗时、缓存命中、WAL 写/同步耗时与次数、IO 等待估算 | 观测到 WAL 成本不等于磁盘饱和；没有 OS 磁盘队列和逐 SQL 耗时，不能认定唯一瓶颈 |",
        "| 秒级长尾 | 每轮最大值和 ≥1 秒请求数，结合 p99 查看稀疏慢请求 | 原始延迟缺少时间戳，无法与 GC、磁盘和等待事件对齐归因；未测试负载不外推 |",
    ]
    lines += [
        "",
        "### 结果边界",
        "",
        f"本次发压位置标记为 {metadata.get('placement', '历史同机 loopback')}，发压主机 {metadata.get('load_generator_host', '未记录')}，服务主机 {server.get('host', '未记录')}。独立接入文件不自动证明物理主机隔离；同机结果受 CPU、磁盘和发压端竞争影响。未测 Apple APNs 或生产容量。",
        "这里的“并发请求”表示 worker 数；“在线连接”只代表本次成功测到的连接级别，不代表最大支持连接数。热点写入受每用户顺序锁串行化，WS 延迟包含持久化与实时通知等待。旧报告中的实例共享 JVM；新夹具使用独立 JVM，但仍共享服务主机和主库。ActiveProcessorCount 是 JVM 调度提示，不能替代 OS CPU 配额。闭环测量不作 coordinated omission 修正，不能推断固定到达率下的容量。",
        "",
    ]
    return "\n\n".join(lines[:3]) + "\n" + "\n".join(lines[3:])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path)
    parser.add_argument(
        "--readme", type=Path, default=Path(__file__).parent / "README.md"
    )
    args = parser.parse_args()
    result = read_result(args.result)
    if result["metadata"]["smoke"]:
        raise SystemExit(
            "smoke results must not replace the published performance baseline"
        )
    source = os.path.relpath(args.result.resolve(), args.readme.parent.resolve())
    section = render(result, source)
    text = (
        args.readme.read_text().split("## 实测结果")[0]
        if args.readme.exists()
        else "# 并发基准实测\n\n"
    )
    args.readme.write_text(text + "## 实测结果\n\n" + section)


if __name__ == "__main__":
    main()
