-- 已部署且尚无通知操作字段的数据库：停写、备份并核对结构后执行一次。
-- 此脚本不会自动在服务启动时执行；重跑会失败并回滚，避免重复回填覆盖当前计数。
BEGIN;
LOCK TABLE snap_flow.im_user_state, snap_flow.im_message IN ACCESS EXCLUSIVE MODE;
ALTER TABLE snap_flow.im_user_state ADD COLUMN notification_unread BIGINT NOT NULL DEFAULT 0 CHECK(notification_unread>=0);
ALTER TABLE snap_flow.im_message ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN is_deleted BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;
UPDATE snap_flow.im_user_state s SET notification_unread=(
 SELECT count(*) FROM snap_flow.im_message m WHERE m.app_id=s.app_id AND m.user_id=s.user_id AND m.seq>s.read_seq);
CREATE INDEX im_message_visible ON snap_flow.im_message(app_id,user_id,seq DESC) WHERE NOT is_deleted;
CREATE INDEX im_message_unread ON snap_flow.im_message(app_id,user_id,seq) WHERE NOT is_read AND NOT is_deleted;
COMMIT;
