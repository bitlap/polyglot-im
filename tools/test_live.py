"""启动临时真实服务，运行 Python/Dart 网络示例，退出时释放进程组。"""

import os
import signal
import socket
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
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
                ["sbt", "scalaServer/Test/runMain polyglot.im.server.DemoServer"],
                cwd=ROOT / "jvm",
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
                        "python-sdk",
                        "--locked",
                        "python",
                        "python-sdk/examples/live.py",
                        str(config),
                    ],
                    cwd=ROOT,
                    check=True,
                )
                subprocess.run(
                    ["dart", "run", "example/live.dart", str(config)],
                    cwd=ROOT / "dart-sdk",
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
