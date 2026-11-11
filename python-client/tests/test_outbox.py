import asyncio
import uuid
from contextlib import asynccontextmanager

import psycopg
import pytest
from psycopg import sql
from psycopg_pool import AsyncConnectionPool

from polyglot_im import ImError, PostgresOutbox, open_postgres_outbox


@asynccontextmanager
async def isolated_outbox(dsn):
    schema = "im_test_" + uuid.uuid4().hex
    async with await psycopg.AsyncConnection.connect(dsn, autocommit=True) as admin:
        await admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
        try:
            async with AsyncConnectionPool(
                dsn,
                open=False,
                min_size=1,
                max_size=8,
                kwargs={"options": f"-csearch_path={schema}"},
            ) as pool:
                await pool.wait()
                box = PostgresOutbox(pool, schema=schema)
                await box.initialize()
                yield box
        finally:
            await admin.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(schema)))


class Sender:
    def __init__(self):
        self.sent = []

    async def send(self, *args):
        self.sent.append(args)


def test_business_rollback_commit_and_worker_restart(postgres_dsn):
    async def run():
        async with isolated_outbox(postgres_dsn) as outbox:
            async with outbox.pool.connection() as conn:
                await conn.execute("CREATE TABLE business_task(id text PRIMARY KEY)")
            with pytest.raises(RuntimeError):
                async with outbox.pool.connection() as conn, conn.transaction():
                    await conn.execute("INSERT INTO business_task VALUES('rolled-back')")
                    await outbox.enqueue(conn, "rollback", "alice", "system.notice", {})
                    raise RuntimeError("business failed")
            async with outbox.pool.connection() as conn:
                assert (
                    await (await conn.execute("SELECT count(*) FROM im_producer_outbox")).fetchone()
                )[0] == 0
                assert (
                    await (await conn.execute("SELECT count(*) FROM business_task")).fetchone()
                )[0] == 0
                await conn.execute("INSERT INTO business_task VALUES('committed')")
                await outbox.enqueue(conn, "stable-key", "alice", "system.notice", {"text": "完成"})
            sender = Sender()
            restarted = PostgresOutbox(outbox.pool, schema=outbox.schema)
            assert await restarted.drain_once(sender)
            assert not await restarted.drain_once(sender)
            assert sender.sent == [("alice", "stable-key", "system.notice", {"text": "完成"})]
            async with outbox.pool.connection() as conn:
                assert (await (await conn.execute("SELECT id FROM business_task")).fetchone()) == (
                    "committed",
                )

    asyncio.run(run())


def test_enqueue_requires_active_transaction_and_rejects_changed_intent(postgres_dsn):
    async def run():
        async with isolated_outbox(postgres_dsn) as box:
            async with box.pool.connection() as conn:
                with pytest.raises(ValueError, match="active"):
                    await box.enqueue(conn, "event", "alice", "system.notice", {})
                async with conn.transaction():
                    with pytest.raises(ValueError, match="stable"):
                        await box.enqueue(conn, "", "alice", "system.notice", {})
                    await box.enqueue(conn, "event", "alice", "system.notice", {"b": 2, "a": 1})
                    await box.enqueue(conn, "event", "alice", "system.notice", {"a": 1, "b": 2})
                    for recipient, payload in [("bob", {"a": 1, "b": 2}), ("alice", {"a": 3})]:
                        with pytest.raises(ValueError, match="reused"):
                            await box.enqueue(conn, "event", recipient, "system.notice", payload)

    asyncio.run(run())


def test_concurrent_enqueue_and_workers_do_not_duplicate_claims(postgres_dsn):
    async def run():
        async with isolated_outbox(postgres_dsn) as box:

            async def enqueue(key):
                async with box.pool.connection() as conn, conn.transaction():
                    await box.enqueue(conn, key, "alice", "system.notice", {})

            await asyncio.gather(*(enqueue("same") for _ in range(8)))
            await asyncio.gather(*(enqueue(str(i)) for i in range(15)))
            sender = Sender()

            async def drain():
                while await box.drain_once(sender):
                    await asyncio.sleep(0)

            await asyncio.gather(*(drain() for _ in range(8)))
            assert len(sender.sent) == 16
            assert len({row[1] for row in sender.sent}) == 16

    asyncio.run(run())


@pytest.mark.parametrize("status,expected", [(403, "dead"), (503, "pending"), (401, "pending")])
def test_send_failure_keeps_intent_and_records_stable_error(postgres_dsn, status, expected):
    async def run():
        async with isolated_outbox(postgres_dsn) as box:
            async with box.pool.connection() as conn, conn.transaction():
                await box.enqueue(conn, "event", "alice", "system.notice", {})

            class Failure:
                async def send(self, *args):
                    raise ImError(status, "TEST_ERROR", "not persisted")

            assert await box.drain_once(Failure())
            assert not await box.drain_once(Failure())
            async with box.pool.connection() as conn:
                row = await (
                    await conn.execute("SELECT state,error,attempts FROM im_producer_outbox")
                ).fetchone()
                assert row == (expected, "TEST_ERROR", 1)

    asyncio.run(run())


def test_cancellation_lease_expiry_and_stale_confirmation(postgres_dsn):
    async def run():
        async with isolated_outbox(postgres_dsn) as box:
            async with box.pool.connection() as conn, conn.transaction():
                await box.enqueue(conn, "event", "alice", "system.notice", {})
            started = asyncio.Event()

            class Blocked:
                async def send(self, *args):
                    started.set()
                    await asyncio.Event().wait()

            task = asyncio.create_task(box.drain_once(Blocked()))
            await asyncio.wait_for(started.wait(), 5)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            assert await box._claim() is None
            async with box.pool.connection() as conn:
                old = await (
                    await conn.execute(
                        "SELECT event_id,recipient,kind,payload,attempts,lease "
                        "FROM im_producer_outbox"
                    )
                ).fetchone()
                await conn.execute("UPDATE im_producer_outbox SET lease_until=0")
            new = await box._claim()
            assert new and new[5] != old[5]
            await box._finish(old, "sent", None)
            async with box.pool.connection() as conn:
                assert (
                    await (
                        await conn.execute("SELECT state,lease FROM im_producer_outbox")
                    ).fetchone()
                ) == ("pending", new[5])
            await box._finish(new, "sent", None)
            assert not await box.drain_once(Sender())

    asyncio.run(run())


def test_owned_factory_closes_and_borrowed_adapter_keeps_pool(postgres_dsn):
    async def run():
        async with open_postgres_outbox(postgres_dsn, min_size=0, max_size=2) as box:
            assert not box.pool.closed
            borrowed = PostgresOutbox(box.pool)
            assert borrowed.pool is box.pool
            async with box.pool.connection() as conn:
                assert (await (await conn.execute("SELECT 1")).fetchone()) == (1,)
        assert box.pool.closed
        with pytest.raises(ValueError, match="explicit"):
            async with open_postgres_outbox(""):
                pass
        with pytest.raises(ValueError, match="lease_seconds"):
            PostgresOutbox(box.pool, lease_seconds=0)

    asyncio.run(run())


def test_explicit_schema_does_not_depend_on_connection_search_path(postgres_dsn):
    async def run():
        async with isolated_outbox(postgres_dsn) as outbox:
            async with outbox.pool.connection() as conn, conn.transaction():
                await conn.execute("SET LOCAL search_path=pg_catalog")
                await outbox.enqueue(conn, "qualified", "alice", "system.notice", {})
            sender = Sender()
            assert await outbox.drain_once(sender)
            assert sender.sent == [("alice", "qualified", "system.notice", {})]
            for schema in ("public; DROP SCHEMA public", "has.dot", "", "A", "x" * 64):
                with pytest.raises(ValueError, match="schema"):
                    PostgresOutbox(outbox.pool, schema=schema)

    asyncio.run(run())
