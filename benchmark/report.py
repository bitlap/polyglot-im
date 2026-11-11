"""从原始样本生成 README 实测表；不平均各轮分位数。"""

import argparse
import json
import os
from pathlib import Path

from .metrics import aggregate_trials

NAMES = {
    "write_many_users": "多用户写入",
    "write_hot_user": "单用户热点写入",
    "history": "历史分页（20 条）",
    "sync_empty": "空增量同步",
    "websocket_delivery": "WS 端到端投递",
}


def render(result: dict, source: str) -> str:
    metadata = result["metadata"]
    server = metadata["server"]
    rows = aggregate_trials(result["trials"])
    lines = [
        f"测试时间：{metadata['timestamp_utc']}。源码提交 `{metadata['git_revision']}`，工作区未提交修改：`{metadata['working_tree_dirty']}`。",
        f"原始环境/每轮指标/逐条延迟样本见 [{Path(source).name}]({source})。",
        "",
        "| 环境 | 本次参数 |",
        "| --- | --- |",
        f"| 操作系统 / 架构 | {metadata['os']} / {metadata['machine']} |",
        f"| CPU / 内存 | {metadata.get('cpu_model', '未采集')}；逻辑 CPU {metadata['logical_cpus']}；物理内存 {metadata.get('memory_bytes', 0) / 2**30:.1f} GiB |",
        f"| Java / 压测 Python | {server['java']} / {metadata['python']} |",
        f"| JVM / 数据库连接池 | 可见 CPU {server['jvm_processors']}，最大堆 {server['max_heap_bytes'] / 2**30:.1f} GiB；连接池 {server['database_pool']} |",
        f"| PostgreSQL | {server['postgres']['version']} |",
        f"| 持久化 | fsync={server['postgres']['fsync']}，synchronous_commit={server['postgres']['synchronous_commit']}，full_page_writes={server['postgres']['full_page_writes']}，wal_sync_method={server['postgres']['wal_sync_method']} |",
        f"| 发压 / 预热 / 重复 | {metadata['load_generator']}；每 HTTP 轮 {metadata['requests_per_trial']} 次；预热 {metadata['warmup_requests']} 次；重复 {metadata['repeats']} 轮 |",
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
            f"| {NAMES[row['scenario']]} | {row['concurrency']} | {row['succeeded']}/{row['attempted']} | {row['error_rate']:.2%} | {row['successful_rps']:.1f} | {row['rps_min']:.1f}–{row['rps_max']:.1f} | {row['p50_ms']:.2f} | {row['p95_ms']:.2f} | {row['p99_ms']:.2f} | {row['max_ms']:.2f} |"
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
            f"| {row['concurrency']} | {row['succeeded']}/{row['attempted']} | {row['error_rate']:.2%} | {row['successful_rps']:.1f} | {row['p50_ms']:.2f} | {row['p95_ms']:.2f} | {row['p99_ms']:.2f} | {row['max_ms']:.2f} |"
        )
    lines += [
        "",
        "### 结果边界",
        "",
        "结果是同一台机器上发压端、JVM 和数据库共同运行的 loopback 基线，使用本机临时磁盘；不是跨机网络、移动弱网、APNs 或生产部署容量。JIT、缓存、磁盘与单进程 Python 发压端均影响数值，各轮范围用于展示波动。",
        "这里的“并发请求”表示 worker 数；“在线连接”只代表本次成功测到的连接级别，不代表最大支持连接数。热点写入受每用户顺序锁串行化，WS 延迟包含当前 500ms 持久流轮询。容量上限还需独立压测机、持续时间/固定到达率及服务器 CPU/磁盘/锁等待观测。",
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
    text = args.readme.read_text().split("## 实测结果")[0]
    args.readme.write_text(text + "## 实测结果\n\n" + section)


if __name__ == "__main__":
    main()
