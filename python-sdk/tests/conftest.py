"""真实 PostgreSQL 测试：CI 可提供专用 DSN，本地使用临时嵌入式 PostgreSQL。"""

import os
import signal
import subprocess
import tempfile
import time
from pathlib import Path

import pytest


@pytest.fixture(scope="session")
def postgres_dsn():
    configured = os.environ.get("POLYGLOT_IM_TEST_POSTGRES_DSN")
    if configured:
        yield configured
        return
    root = Path(__file__).resolve().parents[2]
    with tempfile.TemporaryDirectory(prefix="polyglot-im-pg-test-") as directory:
        runtime = Path(directory)
        with (runtime / "server.log").open("w+") as log:
            server = subprocess.Popen(
                ["sbt", "scalaServer/Test/runMain polyglot.im.server.PostgresTestServer"],
                cwd=root,
                env={**os.environ, "POLYGLOT_IM_TEST_DIRECTORY": directory},
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            try:
                deadline = time.monotonic() + 240
                config = runtime / "dsn"
                while not config.exists():
                    if server.poll() is not None or time.monotonic() > deadline:
                        log.seek(0)
                        raise RuntimeError("temporary PostgreSQL failed:\n" + log.read()[-6000:])
                    time.sleep(0.2)
                yield config.read_text()
            finally:
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGTERM)
                    try:
                        server.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        os.killpg(server.pid, signal.SIGKILL)
                        server.wait()
