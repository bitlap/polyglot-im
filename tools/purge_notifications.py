"""按服务方提供的精确清单清除敏感通知；默认只读预览，不访问业务数据库。"""

import argparse
import hashlib
import json
import os
import uuid
from datetime import UTC, datetime
from pathlib import Path


def validate_manifest(entries):
    """校验最多1000条收件人和生产者幂等键，拒绝无范围或重复的清理。"""
    if not isinstance(entries, list) or not 1 <= len(entries) <= 1000:
        raise ValueError("清单须包含1至1000条通知")
    seen = set()
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"user_id", "client_key"}:
            raise ValueError("清单仅接受 user_id、client_key")
        values = entry["user_id"], entry["client_key"]
        if any(not isinstance(v, str) or not v or len(v) > 200 for v in values):
            raise ValueError("清单身份或幂等键无效")
        if values in seen:
            raise ValueError("清单重复")
        seen.add(values)
    return sorted(seen)


def purge_notifications(connection, app, sender, entries, *, apply=False):
    """锁定账号后清空负载、取消推送并写同步墓碑；事务失败全部回滚，不记录内容。"""
    targets = validate_manifest(entries)
    if not app or not sender:
        raise ValueError("必须指定应用和生产者")
    matched = changed = 0
    with connection.transaction():
        if not apply:
            connection.execute("SET TRANSACTION READ ONLY")
        for user in sorted({user for user, _ in targets}):
            state = connection.execute(
                "SELECT cursor,read_seq FROM snap_flow.im_user_state "
                "WHERE app_id=%s AND user_id=%s" + (" FOR UPDATE" if apply else ""),
                (app, user),
            ).fetchone()
            if state is None:
                continue
            cursor, read_seq = state
            cleared = 0
            for _, key in (target for target in targets if target[0] == user):
                row = connection.execute(
                    "SELECT message_id,seq,type,created_at,payload,is_read,is_deleted "
                    "FROM snap_flow.im_message WHERE app_id=%s AND user_id=%s "
                    "AND sender_id=%s AND client_key=%s",
                    (app, user, sender, key),
                ).fetchone()
                if row is None:
                    continue
                matched += 1
                mid, seq, kind, created, payload, is_read, deleted = row
                if payload == "{}" and is_read and deleted:
                    continue
                changed += 1
                if not apply:
                    continue
                cleared += int(not is_read and not deleted and seq > read_seq)
                cursor += 1
                data = json.dumps(
                    {
                        "schema_version": 1,
                        "message_id": mid,
                        "conversation_id": "notice_"
                        + hashlib.sha256((app + "\0" + user).encode()).hexdigest(),
                        "conversation_seq": str(seq),
                        "type": kind,
                        "payload": {},
                        "created_at": created,
                        "is_read": True,
                        "is_deleted": True,
                        "state_version": str(cursor),
                    },
                    separators=(",", ":"),
                )
                connection.execute(
                    "UPDATE snap_flow.im_message SET payload='{}',is_read=TRUE,"
                    "is_deleted=TRUE,state_version=%s WHERE message_id=%s AND app_id=%s",
                    (cursor, mid, app),
                )
                connection.execute(
                    "UPDATE snap_flow.im_event SET data=%s WHERE app_id=%s AND user_id=%s "
                    "AND data::jsonb->>'message_id'=%s",
                    (data, app, user, mid),
                )
                connection.execute(
                    "UPDATE snap_flow.im_outbox SET state='dead',error='PRIVACY_PURGED',"
                    "lease_until=0,lease_token=NULL WHERE app_id=%s AND user_id=%s AND message_id=%s",
                    (app, user, mid),
                )
                connection.execute(
                    "INSERT INTO snap_flow.im_event(app_id,user_id,cursor,event_id,type,data,created_at) "
                    "VALUES(%s,%s,%s,%s,'notification.updated',%s,%s)",
                    (
                        app,
                        user,
                        cursor,
                        str(uuid.uuid4()),
                        data,
                        datetime.now(UTC).isoformat(),
                    ),
                )
            if apply:
                connection.execute(
                    "UPDATE snap_flow.im_user_state SET cursor=%s,notification_unread="
                    "notification_unread-%s WHERE app_id=%s AND user_id=%s",
                    (cursor, cleared, app, user),
                )
                channel = (
                    "notice_" + hashlib.sha256((app + "\0" + user).encode()).hexdigest()
                )
                connection.execute(
                    "SELECT pg_notify('polyglot_im_changes',%s)", (channel,)
                )
    return {
        "requested": len(targets),
        "matched": matched,
        "changed": changed,
        "applied": apply,
    }


def main():
    """从 IM 专用 DSN 环境变量读取连接，显式 --apply 才修改数据。"""
    import psycopg

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", required=True)
    parser.add_argument("--sender", required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    entries = json.loads(args.manifest.read_text())
    validate_manifest(entries)
    with psycopg.connect(
        os.environ["IM_MAINTENANCE_DSN"], autocommit=True
    ) as connection:
        print(
            json.dumps(
                purge_notifications(
                    connection, args.app, args.sender, entries, apply=args.apply
                )
            )
        )


if __name__ == "__main__":
    main()
