-- 显式部署迁移；不在启动时执行，不删除已产生的账号墓碑。
CREATE TABLE IF NOT EXISTS snap_flow.im_account_tombstone(
 app_id TEXT NOT NULL, -- 应用隔离标识
 user_id TEXT NOT NULL, -- 不再接受登录或写入的最小账号标识
 deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- 不可恢复删除提交时间
 PRIMARY KEY(app_id,user_id)
);
