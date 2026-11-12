"""启动独立 JVM 压测夹具，或读取独立发压机使用的私有接入文件。"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def validate_manifest(config: dict) -> dict:
    """校验专用夹具接入格式；凭证只在内存使用，不进入压测结果。"""
    if config.get("manifest_version") != 2:
        raise ValueError("benchmark manifest_version=2 required")
    for name in ("base_urls", "users", "service_tokens"):
        if not isinstance(config.get(name), list) or not config[name]:
            raise ValueError(f"nonempty {name} required")
    if len(config["base_urls"]) != config["server"]["instances"]:
        raise ValueError("manifest instance count does not match endpoints")
    for target in [*config["base_urls"], config["diagnostics_url"]]:
        uri = urlsplit(target)
        if (
            uri.scheme not in {"http", "https"}
            or not uri.hostname
            or uri.username
            or uri.password
            or uri.query
            or uri.fragment
        ):
            raise ValueError("absolute HTTP(S) benchmark endpoints required")
    nodes = config["server"]["nodes"]
    if len(nodes) != len(config["base_urls"]) or len(
        {node["pid"] for node in nodes}
    ) != len(nodes):
        raise ValueError("each server instance must have a distinct JVM PID")
    if (
        sum(node["pool"] for node in nodes)
        != config["server"]["database_connections_total"]
    ):
        raise ValueError("database connection budget mismatch")
    return config


def owned_group_alive(group_id: int) -> bool:
    """权限探测遭拒时，用进程表确认该组是否还有当前用户的非僵尸进程。"""
    listing = subprocess.check_output(["ps", "-axo", "pgid=,uid=,stat="], text=True)
    for line in listing.splitlines():
        fields = line.split()
        if (
            len(fields) == 3
            and int(fields[0]) == group_id
            and int(fields[1]) == os.getuid()
            and not fields[2].startswith("Z")
        ):
            return True
    return False


@contextmanager
def server(args):
    """启动专用临时数据库与独立服务进程；退出或失败时终止整个夹具进程组。"""
    with tempfile.TemporaryDirectory(prefix="polyglot-im-benchmark-") as temporary:
        path = args.serve_only.resolve() if args.serve_only else Path(temporary)
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
        path.chmod(0o700)
        if (path / "config.json").exists():
            raise ValueError(
                "serve-only directory must not contain a previous config.json"
            )
        names = {
            "INSTANCES": args.instances,
            "DB_CONNECTIONS": args.db_connections,
            "SERVER_CPUS": args.server_cpus,
            "SERVER_HEAP_MIB": args.server_heap_mib,
            "PRODUCERS": args.producers,
            "USERS": args.users,
            "BIND": args.bind,
            "ADVERTISE_HOST": args.advertise_host,
            "DIRECTORY": path,
        }
        env = {
            **os.environ,
            **{f"POLYGLOT_IM_BENCH_{key}": str(value) for key, value in names.items()},
        }
        with (path / "server.log").open("w+") as log:
            process = subprocess.Popen(
                ["sbt", "run"],
                cwd=ROOT / "benchmark",
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + 240
                manifest = path / "config.json"
                while not manifest.exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        log.seek(0)
                        raise RuntimeError(
                            "benchmark startup failed:\n" + log.read()[-8000:]
                        )
                    time.sleep(0.2)
                config = validate_manifest(json.loads(manifest.read_text()))
                config["server"]["fixture_revision"] = subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
                ).strip()
                config["server"]["fixture_working_tree_dirty"] = bool(
                    subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)
                )
                # 接入文件包含随机测试凭证，保持仅当前用户可读；发布结果只提取 server 元数据。
                manifest.write_text(json.dumps(config, ensure_ascii=False))
                manifest.chmod(0o600)
                yield config, process
            finally:
                # 即使 sbt/协调进程提前退出，进程组仍可能包含存活子 JVM。
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    process.wait(timeout=25)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    try:
                        os.killpg(process.pid, 0)
                    except ProcessLookupError:
                        break
                    except PermissionError:
                        # 组探测可能遭拒，但自有子进程仍在正常退出；用进程表等完宽限期。
                        if not owned_group_alive(process.pid):
                            break
                    time.sleep(0.1)
                else:
                    if owned_group_alive(process.pid):
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        deadline = time.monotonic() + 10
                        while owned_group_alive(process.pid):
                            if time.monotonic() >= deadline:
                                raise RuntimeError(
                                    "benchmark child processes did not exit after SIGKILL"
                                )
                            time.sleep(0.1)
