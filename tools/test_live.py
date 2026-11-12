"""启动临时真实服务，运行 Scala/Python/Dart/uni-app 网络示例，退出时释放进程组。"""

import os
import signal
import socket
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    node_major = int(
        subprocess.check_output(
            ["node", "-p", "process.versions.node.split('.')[0]"], text=True
        )
    )
    if node_major < 22:
        raise RuntimeError("uni-app 真实网络验收需要 Node 22+（内置 fetch/WebSocket）")
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix="polyglot-im-integration-") as directory:
        runtime = Path(directory)
        env = {
            **os.environ,
            "POLYGLOT_IM_DEMO_PORT": str(port),
            "POLYGLOT_IM_DEMO_DIRECTORY": str(runtime),
        }
        with (runtime / "server.log").open("w+") as log:
            server = subprocess.Popen(
                ["sbt", "run"],
                cwd=ROOT / "examples/scala-server",
                env=env,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + 180
                config = runtime / "config.json"
                while not config.is_file():
                    if server.poll() is not None or time.monotonic() > deadline:
                        log.seek(0)
                        raise RuntimeError(
                            "demo startup failed:\n" + log.read()[-6000:]
                        )
                    time.sleep(0.2)
                subprocess.run(
                    [
                        "uv",
                        "run",
                        "--project",
                        "python-client",
                        "--locked",
                        "python",
                        "examples/python-client/live.py",
                        str(config),
                    ],
                    cwd=ROOT,
                    check=True,
                )
                subprocess.run(
                    ["dart", "run", "live.dart", str(config)],
                    cwd=ROOT / "examples/dart-client",
                    check=True,
                )
                subprocess.run(
                    ["sbt", f"run {config}"],
                    cwd=ROOT / "examples/scala-client",
                    check=True,
                )
                subprocess.run(
                    ["python3", "tools/pack_uniapp.py"], cwd=ROOT, check=True
                )
                version = (ROOT / "VERSION").read_text().strip()
                package = (
                    ROOT / f"dist/uniapp-client/polyglot-im-uniapp-client-{version}.tgz"
                )
                consumer = runtime / "uniapp-consumer"
                subprocess.run(
                    [
                        "npm",
                        "install",
                        "--prefix",
                        str(consumer),
                        "--ignore-scripts",
                        "--no-package-lock",
                        "--no-audit",
                        "--no-fund",
                        str(package),
                    ],
                    cwd=runtime,
                    check=True,
                )
                subprocess.run(
                    ["node", "examples/uniapp-client/live.mjs", str(config)],
                    cwd=ROOT,
                    env={
                        **env,
                        "POLYGLOT_IM_UNIAPP_ENTRY": str(
                            consumer
                            / "node_modules/@polyglot-im/uniapp-client/index.js"
                        ),
                    },
                    check=True,
                )
            finally:
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGTERM)
                    try:
                        server.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(server.pid, signal.SIGKILL)
                        server.wait()


if __name__ == "__main__":
    main()
