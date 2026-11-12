import asyncio
import json
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

import psycopg
import pytest
from psycopg import sql
from psycopg_pool import AsyncConnectionPool

from polyglot_im import PostgresStore, Scope, open_postgres_store

SCOPE = Scope("https://im.example", "app", "alice")
FIXTURES = Path(__file__).resolve().parents[2] / "protocol/fixtures"


def fixture(name):
    return json.loads((FIXTURES / name).read_text())


def message(index):
    return fixture("message.json") | {
        "message_id": f"pg-message-{index}",
        "conversation_seq": str(index),
        "type": "custom.future",
        "payload": {"text": "你好 👨‍👩‍👧‍👦", "future": "kept"},
    }


@asynccontextmanager
async def database(dsn):
    schema = "im_test_" + uuid.uuid4().hex
    async with await psycopg.AsyncConnection.connect(dsn, autocommit=True) as admin:
        try:
            async with open_postgres_store(dsn, namespace="device", schema=schema) as store:
                yield store, admin
        finally:
            await admin.execute(
                sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema))
            )


def test_restart_namespace_identity_isolation_and_borrowed_pool(postgres_dsn):
    async def run():
        async with database(postgres_dsn) as (store, _):
            saved = await store.commit(SCOPE, fixture("sync.json"))
            assert saved["latest_seq"] == "9007199254740993"
            async with open_postgres_store(
                postgres_dsn, namespace="device", schema=store.schema
            ) as restarted:
                assert await restarted.load(SCOPE) == saved
            # 关闭拥有的池不影响另一个池，借入的池在替换存储后仍由宿主管理。
            other = PostgresStore(store.pool, namespace="other", schema=store.schema)
            assert not (await other.load(SCOPE))["messages"]
            for scope in [
                Scope("https://other", "app", "alice"),
                Scope(SCOPE.environment, "other", "alice"),
                Scope(SCOPE.environment, "app", "bob"),
            ]:
                assert not (await store.load(scope))["messages"]
                await store.commit(scope, {"cursor": scope.key})
            await other.commit(SCOPE, {"cursor": "other"})
            await store.clear(SCOPE)
            assert (await other.load(SCOPE))["cursor"] == "other"
            assert not (await store.load(SCOPE))["messages"]

    asyncio.run(run())


def test_concurrent_first_writes_from_independent_pools_and_reinitialization(postgres_dsn):
    async def run():
        async with database(postgres_dsn) as (store, _):
            async with AsyncConnectionPool(
                postgres_dsn, open=False, min_size=1, max_size=8
            ) as second:
                await second.wait()
                stores = [
                    PostgresStore(
                        store.pool if i % 2 else second, namespace="device", schema=store.schema
                    )
                    for i in range(16)
                ]
                await asyncio.gather(*(item.initialize() for item in stores))
                await asyncio.gather(
                    *(
                        item.commit(SCOPE, {"messages": [message(i + 1)], "latest_seq": str(i + 1)})
                        for i, item in enumerate(stores)
                    )
                )
            saved = await store.load(SCOPE)
            assert len(saved["messages"]) == 16
            assert saved["latest_seq"] == "16"
            assert saved["messages"]["pg-message-1"]["payload"]["future"] == "kept"

    asyncio.run(run())


def test_sql_failure_reset_and_corrupt_clear_are_atomic(postgres_dsn):
    async def run():
        async with database(postgres_dsn) as (store, admin):
            saved = await store.commit(SCOPE, fixture("sync.json"))
            with pytest.raises((KeyError, ValueError, TypeError)):
                await store.commit(SCOPE, {"cursor": "invalid", "messages": [{}]})
            await admin.execute(
                f"CREATE FUNCTION {store.schema}.reject_cache() RETURNS trigger LANGUAGE plpgsql "
                "AS $$ BEGIN RAISE EXCEPTION 'test write failure'; END $$"
            )
            await admin.execute(
                f"CREATE TRIGGER reject_cache BEFORE UPDATE ON {store.schema}.im_client_cache "
                f"FOR EACH ROW EXECUTE FUNCTION {store.schema}.reject_cache()"
            )
            with pytest.raises(psycopg.Error):
                await store.commit(SCOPE, {"cursor": "failed"})
            assert await store.load(SCOPE) == saved
            await admin.execute(f"DROP TRIGGER reject_cache ON {store.schema}.im_client_cache")
            reset = await store.commit(SCOPE, {"messages": [message(2)]}, reset=True)
            assert set(reset["messages"]) == {"pg-message-2"}
            assert reset["cursor"] is None
            await admin.execute(
                f"UPDATE {store.schema}.im_client_cache SET data=%s "
                "WHERE namespace=%s AND scope=%s",
                ("{broken", store.namespace, SCOPE.key),
            )
            with pytest.raises(json.JSONDecodeError):
                await store.load(SCOPE)
            await store.clear(SCOPE)
            assert not (await store.load(SCOPE))["messages"]
            assert (await store.commit(SCOPE, {"cursor": "retry"}))["cursor"] == "retry"

    asyncio.run(run())


def test_lock_timeout_cancel_and_read_do_not_corrupt_cache(postgres_dsn):
    async def run():
        async with database(postgres_dsn) as (store, admin):
            saved = await store.commit(SCOPE, {"cursor": "saved"})
            limited = PostgresStore(
                store.pool, namespace=store.namespace, schema=store.schema, lock_timeout_ms=100
            )
            async with admin.transaction():
                await admin.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(%s,674239012))",
                    (store.namespace + "\n" + SCOPE.key,),
                )
                assert await asyncio.wait_for(store.load(SCOPE), 2) == saved
                with pytest.raises(psycopg.errors.LockNotAvailable):
                    await limited.commit(SCOPE, {"cursor": "failed"})
                pending = asyncio.create_task(store.commit(SCOPE, {"cursor": "cancelled"}))
                await asyncio.sleep(0.05)
                pending.cancel()
                with pytest.raises(asyncio.CancelledError):
                    await pending
            assert await store.load(SCOPE) == saved
            assert (await store.commit(SCOPE, {"cursor": "recovered"}))["cursor"] == "recovered"

    asyncio.run(run())


@pytest.mark.parametrize(
    "settings",
    [
        {"namespace": ""},
        {"namespace": "x" * 201},
        {"namespace": "x", "schema": "public;DROP SCHEMA public"},
        {"namespace": "x", "lock_timeout_ms": 0},
        {"namespace": "x", "lock_timeout_ms": True},
    ],
)
def test_invalid_store_configuration(settings):
    with pytest.raises(ValueError):
        PostgresStore(None, **settings)
