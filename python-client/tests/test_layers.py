"""防止以后添加适配器时破坏核心层边界。"""

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from check_layers import violations  # noqa: E402
from check_names import violations as name_violations  # noqa: E402


@pytest.mark.parametrize(
    ("relative", "content"),
    [
        ("service/client.js", "import { uniTransport } from '../adapters/uni.js'\n"),
        ("service/platform.js", "uni.request({})\n"),
        ("service/client.py", "from ..adapters.httpx import HttpxTransport\n"),
        ("ports/store.py", "import sqlite3\n"),
        ("service/Client.scala", "package polyglot.im.service\nimport java.net.http.HttpClient\n"),
        (
            "domain/Model.scala",
            "package polyglot.im.domain\nimport polyglot.im.infrastructure.codec.JsonWireCodec\n",
        ),
        ("service/client.dart", "import 'package:http/http.dart';\n"),
        ("ports/store.dart", "import '../adapters/sqlite.dart';\n"),
    ],
)
def test_reverse_dependency_or_driver_is_rejected(tmp_path, relative, content):
    base = tmp_path / "im"
    path = base / relative
    path.parent.mkdir(parents=True)
    path.write_text(content)
    assert violations(path, base)


def test_application_service_can_depend_on_pure_ports(tmp_path):
    base = tmp_path / "im"
    path = base / "service/client.py"
    path.parent.mkdir(parents=True)
    path.write_text("from ..ports.client_cache import ClientCache\n")
    assert violations(path, base) == []


@pytest.mark.parametrize(
    ("relative", "content", "rejected"),
    [
        ("adapters/cache/sqlite_cache.py", "class SqliteCache: pass", False),
        ("adapters/store/postgres_outbox.py", "class PostgresOutbox: pass", False),
        ("infrastructure/cache/SqliteCache.scala", "class SqliteCache", False),
        ("adapters/cache/sqlite_store.dart", "class SqliteStore {}", True),
        ("adapters/store/postgres_cache.py", "class PostgresCache: pass", True),
        ("adapters/cache/postgres_outbox.py", "class PostgresOutbox: pass", True),
        ("adapters/storage/sqlite.py", "class SqliteCache: pass", True),
        ("adapters/cache/SqliteCache.py", "class SqliteCache: pass", True),
    ],
)
def test_persistence_names_and_packages(tmp_path, relative, content, rejected):
    path = tmp_path / relative
    path.parent.mkdir(parents=True)
    path.write_text(content)
    assert bool(name_violations(path, tmp_path)) is rejected
