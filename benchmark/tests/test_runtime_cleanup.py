import json
import signal
from pathlib import Path

import pytest

from benchmark import runtime
from benchmark.run import parse_args


def test_group_probe_distinguishes_live_owned_processes_from_zombies_and_foreign_groups(
    monkeypatch,
):
    monkeypatch.setattr(runtime.os, "getuid", lambda: 501)
    monkeypatch.setattr(
        runtime.subprocess,
        "check_output",
        lambda *args, **kwargs: "123 501 Z\n123 999 S\n124 501 S\n",
    )
    assert runtime.owned_group_alive(123) is False
    monkeypatch.setattr(
        runtime.subprocess, "check_output", lambda *args, **kwargs: "123 501 S\n"
    )
    assert runtime.owned_group_alive(123) is True


@pytest.mark.parametrize("owned_alive", [False, True])
def test_permission_denial_only_ignored_after_owned_process_group_has_exited(
    monkeypatch, owned_alive
):
    class Process:
        pid = 123

        def __init__(self, *args, **kwargs):
            directory = Path(kwargs["env"]["POLYGLOT_IM_BENCH_DIRECTORY"])
            (directory / "config.json").write_text(
                json.dumps(
                    {
                        "manifest_version": 2,
                        "base_url": "http://localhost:1",
                        "base_urls": ["http://localhost:1"],
                        "diagnostics_url": "http://localhost:2",
                        "users": [{"id": "test"}],
                        "service_tokens": ["synthetic"],
                        "server": {
                            "instances": 1,
                            "database_connections_total": 8,
                            "nodes": [{"pid": 1, "pool": 8}],
                        },
                    }
                )
            )

        def wait(self, **kwargs):
            return 0

    def killpg(group, sig):
        assert group == 123
        if sig == 0:
            raise PermissionError("group probe denied")
        assert sig == signal.SIGTERM

    def output(command, **kwargs):
        if command[0] == "ps":
            return "123 501 S\n" if owned_alive else "123 501 Z\n"
        return "baseline\n" if command[1] == "rev-parse" else b""

    monkeypatch.setattr(runtime.subprocess, "Popen", Process)
    monkeypatch.setattr(runtime.subprocess, "check_output", output)
    monkeypatch.setattr(runtime.os, "killpg", killpg)
    monkeypatch.setattr(runtime.os, "getuid", lambda: 501)
    if owned_alive:
        with pytest.raises(PermissionError), runtime.server(parse_args([])):
            pass
    else:
        with runtime.server(parse_args([])) as (config, _):
            assert config["server"]["fixture_revision"] == "baseline"
