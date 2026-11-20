-- 已有部署先备份、核对结构并显式执行；不删除消息，不改变阅读水位。
ALTER TABLE snap_flow.im_direct_settings ADD COLUMN IF NOT EXISTS read_receipts_visible BOOLEAN NOT NULL DEFAULT TRUE;
