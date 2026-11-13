"""维护清理使用自有临时 PostgreSQL 验证，未配置时只运行清单校验。"""

import json
import os
from pathlib import Path

import psycopg
import pytest
from purge_notifications import purge_notifications, validate_manifest


def test_manifest_rejects_unbounded_or_ambiguous_targets():
    """缺少收件人、空范围、重复记录及超量清单不能进入数据库。"""
    for value in ([], {}, [{"client_key": "x"}], [{"user_id": "", "client_key": "x"}]):
        with pytest.raises(ValueError):
            validate_manifest(value)
    entry = {"user_id": "user", "client_key": "key"}
    for value in ([entry, entry], [entry] * 1001):
        with pytest.raises(ValueError):
            validate_manifest(value)


@pytest.fixture
def database():
    """仅允许显式指定的本地专用 im_privacy_test，重建独立 schema。"""
    dsn = os.environ.get("IM_MAINTENANCE_TEST_DSN")
    if not dsn:
        pytest.skip("需要自有临时 PostgreSQL")
    with psycopg.connect(dsn, autocommit=True) as c:
        assert c.info.dbname == "im_privacy_test" and c.info.host in (
            "127.0.0.1",
            "localhost",
        )
        c.execute("DROP SCHEMA IF EXISTS snap_flow CASCADE; CREATE SCHEMA snap_flow")
        schema = (
            Path(__file__).parents[1] / "scala-server/src/main/resources/db/schema.sql"
        )
        c.execute(
            "CREATE TABLE snap_flow.im_schema(version INTEGER PRIMARY KEY CHECK(version=1))"
        )
        c.execute(schema.read_text())
        c.execute(
            "INSERT INTO snap_flow.im_user_state(app_id,user_id,cursor,message_seq,notification_unread) "
            "VALUES('app','user',3,3,2)"
        )
        for index in range(1, 4):
            mid = f"m{index}"
            c.execute(
                "INSERT INTO snap_flow.im_message(message_id,app_id,user_id,seq,sender_id,client_key,"
                "fingerprint,type,payload,created_at,is_read) "
                "VALUES(%s,'app','user',%s,'producer',%s,'fingerprint','comment.replied',%s,"
                "'2026-11-13T00:00:00Z',%s)",
                (mid, index, f"k{index}", json.dumps({"text": "SECRET"}), index == 2),
            )
            c.execute(
                "INSERT INTO snap_flow.im_event VALUES('app','user',%s,%s,'message.created',%s,'now')",
                (
                    index,
                    mid,
                    json.dumps({"message_id": mid, "payload": {"text": "SECRET"}}),
                ),
            )
            c.execute(
                "INSERT INTO snap_flow.im_outbox(id,app_id,user_id,message_id,device_id,next_at) "
                "VALUES(%s,'app','user',%s,'device',0)",
                (mid, mid),
            )
        yield c


def test_purge_scrubs_events_preserves_other_messages_and_is_idempotent(database):
    """清理历史和推送，生成同步墓碑，已读数只扣未读且重跑不重复变更。"""
    c = database
    entries = [{"user_id": "user", "client_key": key} for key in ("k1", "k2")]
    preview = purge_notifications(c, "app", "producer", entries)
    assert preview == {"requested": 2, "matched": 2, "changed": 2, "applied": False}
    assert c.execute("SELECT cursor FROM snap_flow.im_user_state").fetchone()[0] == 3
    result = purge_notifications(c, "app", "producer", entries, apply=True)
    assert result["changed"] == 2
    assert c.execute(
        "SELECT cursor,message_seq,read_seq,notification_unread FROM snap_flow.im_user_state"
    ).fetchone() == (5, 3, 0, 1)
    assert (
        c.execute(
            "SELECT count(*) FROM snap_flow.im_message WHERE payload='{}' AND is_deleted AND is_read"
        ).fetchone()[0]
        == 2
    )
    assert (
        c.execute(
            "SELECT count(*) FROM snap_flow.im_event WHERE data LIKE '%SECRET%' "
            "AND data::jsonb->>'message_id' IN ('m1','m2')"
        ).fetchone()[0]
        == 0
    )
    assert (
        c.execute(
            "SELECT count(*) FROM snap_flow.im_outbox WHERE state='dead'"
        ).fetchone()[0]
        == 2
    )
    assert (
        c.execute(
            "SELECT is_deleted FROM snap_flow.im_message WHERE message_id='m3'"
        ).fetchone()[0]
        is False
    )
    assert (
        c.execute(
            "SELECT data::jsonb->>'state_version' FROM snap_flow.im_event WHERE cursor=5"
        ).fetchone()[0]
        == "5"
    )
    assert (
        purge_notifications(c, "app", "producer", entries, apply=True)["changed"] == 0
    )
    assert c.execute("SELECT cursor FROM snap_flow.im_user_state").fetchone()[0] == 5


def test_wrong_app_sender_or_recipient_cannot_purge(database):
    """应用、生产者与收件人三重范围必须同时匹配。"""
    c = database
    entries = [{"user_id": "user", "client_key": "k1"}]
    for app, sender, rows in (
        ("other", "producer", entries),
        ("app", "other", entries),
        ("app", "producer", [{"user_id": "other", "client_key": "k1"}]),
    ):
        assert purge_notifications(c, app, sender, rows, apply=True)["matched"] == 0
    assert (
        c.execute(
            "SELECT count(*) FROM snap_flow.im_message WHERE is_deleted"
        ).fetchone()[0]
        == 0
    )


def test_error_rolls_back_all_mutations(database):
    """中途失败不留下清理负载但缺少同步事件的半成品。"""
    c = database
    c.execute(
        "ALTER TABLE snap_flow.im_event ADD CONSTRAINT reject_new_event CHECK(cursor<=3)"
    )
    with pytest.raises(psycopg.errors.CheckViolation):
        purge_notifications(
            c, "app", "producer", [{"user_id": "user", "client_key": "k1"}], apply=True
        )
    assert (
        c.execute(
            "SELECT payload FROM snap_flow.im_message WHERE message_id='m1'"
        ).fetchone()[0]
        != "{}"
    )
    assert c.execute(
        "SELECT cursor,notification_unread FROM snap_flow.im_user_state"
    ).fetchone() == (3, 2)
