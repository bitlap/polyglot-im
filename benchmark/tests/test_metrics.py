import math

import pytest

from benchmark.metrics import percentile, summarize
from benchmark.run import parse_args


def test_percentiles_use_rank_and_keep_tail_spike():
    values = [1.0] * 95 + [10.0] * 4 + [1000.0]
    assert percentile(values, 50) == 1
    assert percentile(values, 95) == 1
    assert percentile(values, 99) == 10
    assert percentile(values, 100) == 1000
    assert percentile([], 99) is None
    with pytest.raises(ValueError):
        percentile(values, 0)


def test_failed_requests_do_not_inflate_successful_throughput():
    result = summarize([10, 20], attempted=4, elapsed_seconds=2)
    assert result["successful_rps"] == 1
    assert result["failed"] == 2
    assert result["error_rate"] == 0.5
    assert summarize([], 2, 1)["p99_ms"] is None
    for samples, attempted, elapsed in [([math.nan], 1, 1), ([1], 0, 1), ([1], 1, 0)]:
        with pytest.raises(ValueError):
            summarize(samples, attempted, elapsed)


def test_smoke_is_small_and_capacity_inputs_are_bounded(tmp_path):
    args = parse_args(["--smoke", "--output", str(tmp_path / "smoke.json")])
    assert args.requests == 16 and args.repeats == 1
    assert args.concurrency == [1, 4]
    for params in [
        ["--requests", "0"],
        ["--instances", "9"],
        ["--instances", "0"],
        ["--concurrency", "257"],
        ["--concurrency", "-1"],
    ]:
        with pytest.raises(SystemExit):
            parse_args(params)


def test_merge_uses_raw_samples_and_weighted_elapsed():
    from benchmark.metrics import aggregate_trials

    trials = [
        {
            "scenario": "history",
            "concurrency": 1,
            "latencies_ms": [1] * 99 + [1000],
            "attempted": 100,
            "elapsed_seconds": 1,
            "successful_rps": 100,
        },
        {
            "scenario": "history",
            "concurrency": 1,
            "latencies_ms": [2] * 100,
            "attempted": 100,
            "elapsed_seconds": 9,
            "successful_rps": 100 / 9,
        },
    ]
    result = aggregate_trials(trials)[0]
    assert result["successful_rps"] == 20
    assert result["p99_ms"] == 2
    assert result["max_ms"] == 1000


def test_multi_instance_batch_sends_to_both_nodes():
    import asyncio

    import httpx

    from benchmark.run import batch

    seen = []

    def respond(request):
        seen.append(request.url.host)
        return httpx.Response(201, json={"message_id": "id"})

    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as http:
            config = {
                "base_url": "http://a",
                "base_urls": ["http://a", "http://b"],
                "users": [{"id": "user"}],
                "service_token": "synthetic",
            }
            result = await batch(http, config, "write_many_users", 2, 4, [])
            assert result["succeeded"] == 4

    asyncio.run(run())
    assert set(seen) == {"a", "b"}
