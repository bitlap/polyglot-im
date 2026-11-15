"""纯派发测试使用替代端口，确认核心无需数据库适配器或网络连接。"""

import asyncio

import pytest

from polyglot_im import (
    ImError,
    NotificationIntent,
    OutboxClaim,
    OutboxDispatcher,
    OutboxState,
)


class MemoryOutbox:
    """只用于派发契约测试的端口替身，不冒充可靠业务存储。"""

    def __init__(self, row):
        self.row, self.completed, self.leases = row, [], []

    async def claim(self, lease_seconds):
        self.leases.append(lease_seconds)
        return self.row

    async def complete(self, row, result):
        self.completed.append((row, result))
        return False  # 模拟租约已被重新领取；派发不绕过存储围栏。


class Publisher:
    def __init__(self, failure=None):
        self.failure, self.sent = failure, []

    async def send(self, *args):
        self.sent.append(args)
        if self.failure is not None:
            raise self.failure


@pytest.mark.parametrize(
    ("failure", "state", "code"),
    [
        (None, OutboxState.SENT, None),
        (ImError(400, "INVALID", "private"), OutboxState.DEAD, "INVALID"),
        (ImError(403, "DENIED", "private"), OutboxState.DEAD, "DENIED"),
        (ImError(409, "CONFLICT", "private"), OutboxState.DEAD, "CONFLICT"),
        (ImError(413, "LARGE", "private"), OutboxState.DEAD, "LARGE"),
        (ImError(422, "INVALID", "private"), OutboxState.DEAD, "INVALID"),
        (ImError(401, "AUTH", "private"), OutboxState.PENDING, "AUTH"),
        (ImError(503, "UNAVAILABLE", "private"), OutboxState.PENDING, "UNAVAILABLE"),
        (OSError("private"), OutboxState.PENDING, "NETWORK_ERROR"),
        (TimeoutError("private"), OutboxState.PENDING, "NETWORK_ERROR"),
    ],
)
def test_dispatch_without_database_and_stable_error_codes(failure, state, code):
    async def run():
        row = OutboxClaim(
            NotificationIntent("stable-id", "alice", "task.completed", {}), 9, "lease"
        )
        store, publisher = MemoryOutbox(row), Publisher(failure)
        assert await OutboxDispatcher(store, publisher, lease_seconds=23).drain_once()
        assert store.leases == [23]
        assert publisher.sent == [("alice", "stable-id", "task.completed", {})]
        claimed, completion = store.completed[0]
        assert claimed is row
        assert (completion.state, completion.error, completion.retry_delay_seconds) == (
            state,
            code,
            300,
        )

    asyncio.run(run())


def test_empty_unknown_failure_and_cancellation_do_not_confirm():
    async def run():
        store, publisher = MemoryOutbox(None), Publisher()
        assert not await OutboxDispatcher(store, publisher).drain_once()
        assert not store.completed and not publisher.sent
        store.row = OutboxClaim(NotificationIntent("id", "alice", "system.notice", {}), 0, "lease")
        for failure in (RuntimeError("unknown"), asyncio.CancelledError()):
            with pytest.raises(type(failure)):
                await OutboxDispatcher(store, Publisher(failure)).drain_once()
            assert not store.completed

    asyncio.run(run())


@pytest.mark.parametrize("duration", [0, -1, True, 1.5])
def test_invalid_lease_is_rejected_before_claim(duration):
    with pytest.raises(ValueError, match="lease_seconds"):
        OutboxDispatcher(MemoryOutbox(None), Publisher(), lease_seconds=duration)
