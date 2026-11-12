"""从原始样本生成 README 实测表；不平均各轮分位数。"""

import argparse
import json
import os
from pathlib import Path

from .metrics import aggregate_trials

NAMES = {
    "write_many_users": "单生产者 → 多接收者",
    "write_hot_user": "单生产者 → 单接收者",
    "write_many_producers_many_users": "多生产者 → 多接收者",
    "write_many_producers_hot_user": "多生产者 → 单接收者",
    "history": "历史分页（20 条）",
    "sync_empty": "空增量同步",
    "websocket_delivery": "WS 端到端投递",
}


def display(value, digits=2):
    """格式化可缺失的延迟指标，全部请求失败时不把缺失分位数记为零。"""
    return "未采集" if value is None else f"{value:.{digits}f}"


def render(result: dict, source: str) -> str:
    """从原始样本生成中文指标表，区分历史拓扑、资源总预算与缺失观测。"""
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
    lines = [
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
        f"| WS / 用户 / 限流 | 每连接 {metadata['ws_messages_per_connection']} 条；{metadata.get('active_users', 256)} 用户；限流阈值 {server['rate_limit_per_minute']}/分钟 |",
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

            def number(name, factor=1, diagnostic=diagnostic):
                value = diagnostic.get(name)
                return "未采集" if value is None else f"{value / factor:.2f}"

            complete = diagnostic.get("complete", False) and not row.get(
                "observation_errors"
            )
            lines.append(
                f"| {NAMES[row['scenario']]} | {row['concurrency']} | {row['repeat']} | {'是' if complete else '否'} | {display(row.get('client_cpu_seconds') / row['elapsed_seconds'] if row.get('client_cpu_seconds') is not None else None)} | {number('server_cpu_cores')} | {number('postgres_cpu_cores_observed')} | {number('pool_acquire_mean_ms')} | {number('pool_waiting_peak')} | {number('blocked_backends_peak')} | {number('gc_millis')} | {number('wal_bytes', 2**20)} |"
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
    lines += [
        "",
        "### 结果边界",
        "",
        f"本次发压位置标记为 {metadata.get('placement', '历史同机 loopback')}，发压主机 {metadata.get('load_generator_host', '未记录')}，服务主机 {server.get('host', '未记录')}。独立接入文件不自动证明物理主机隔离；同机结果受 CPU、磁盘和发压端竞争影响。未测 Apple APNs 或生产容量。",
        "这里的“并发请求”表示 worker 数；“在线连接”只代表本次成功测到的连接级别，不代表最大支持连接数。热点写入受每用户顺序锁串行化，WS 延迟包含持久化与实时通知等待。旧报告中的实例共享 JVM；新夹具使用独立 JVM，但仍共享服务主机和主库。ActiveProcessorCount 是 JVM 调度提示，不能替代 OS CPU 配额。闭环测量不作 coordinated omission 修正，不能推断固定到达率下的容量。",
        "",
    ]
    return "\n\n".join(lines[:2]) + "\n" + "\n".join(lines[2:])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("result", type=Path)
    parser.add_argument(
        "--readme", type=Path, default=Path(__file__).parent / "README.md"
    )
    args = parser.parse_args()
    result = json.loads(args.result.read_text())
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
