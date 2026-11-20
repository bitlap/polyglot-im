-- 已有数据库启用单聊控制：停写、备份并核对结构后，由部署者在事务中执行。
-- 不改写历史消息，不删除本人偏好或既有激活标记。
-- 单聊消息状态版本，撤回时递增，用于阻止旧历史覆盖新状态。
ALTER TABLE snap_flow.im_direct_message ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;
-- im_direct_settings：用户级私信接收与在线可见范围，不承载业务关注关系。
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_settings (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 inbound_scope TEXT NOT NULL DEFAULT 'mutual' CHECK(inbound_scope IN ('all','following','mutual')), -- 接收范围 all 为所有人，following 为本人关注的人，mutual 为互关
 online_visibility TEXT NOT NULL DEFAULT 'mutual' CHECK(online_visibility IN ('mutual','off')), -- 在线可见范围 mutual 为满足双方互关与公开条件时可见，off 为关闭
 PRIMARY KEY(app_id,user_id)
);
-- im_direct_preference：本人对某用户的偏好与会话隐藏、清空边界，不向对方公开拉黑标记。
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_preference (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 peer_id TEXT NOT NULL, -- 偏好对应的另一用户，不能等于本人
 blocked BOOLEAN NOT NULL DEFAULT FALSE, -- 本人是否拉黑对方，发送和在线可见性校验均检查双向状态
 muted BOOLEAN NOT NULL DEFAULT FALSE, -- 本会话免打扰，抑制系统推送而不清除站内未读
 pinned BOOLEAN NOT NULL DEFAULT FALSE, -- 本人是否置顶会话，供客户端排序
 hidden_through BIGINT NOT NULL DEFAULT -1, -- 隐藏截至该会话序号，-1 表示未设置隐藏边界，新消息可使会话重现
 clear_seq BIGINT NOT NULL DEFAULT 0, -- 本人清空记录的会话序号边界，边界及之前不再可见，不推进对方回执
 PRIMARY KEY(app_id,user_id,peer_id),
 CHECK(user_id<>peer_id)
);
-- im_direct_deleted：本人单条删除的同步墓碑，防止迟到历史页恢复消息，不删除对方副本。
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_deleted (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 执行个人删除的用户标识
 conversation_id TEXT NOT NULL, -- 单聊会话标识
 message_id TEXT NOT NULL, -- 服务端生成的消息唯一标识
 state_version BIGINT NOT NULL, -- 个人删除状态版本，用于可靠同步及缓存归并
 PRIMARY KEY(app_id,user_id,message_id),
 FOREIGN KEY(app_id,conversation_id) REFERENCES snap_flow.im_direct_conversation(app_id,conversation_id)
);
-- im_presence：设备在线租约，只有有效设备且租约未到期才可计入在线。
CREATE TABLE IF NOT EXISTS snap_flow.im_presence (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 device_id TEXT NOT NULL, -- 客户端安装身份，不是 APNs token 或硬件标识
 lease_until BIGINT NOT NULL, -- 在线租约到期时间，Unix 秒，前台续约后最多维持 60 秒
 PRIMARY KEY(app_id,user_id,device_id),
 FOREIGN KEY(app_id,user_id,device_id) REFERENCES snap_flow.im_device(app_id,user_id,device_id)
);
CREATE INDEX IF NOT EXISTS im_presence_expiry ON snap_flow.im_presence(lease_until);
-- im_direct_activation：单聊权限控制持久激活标记，存在真值后禁止退回关闭控制模式。
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_activation(
 enabled BOOLEAN PRIMARY KEY CHECK(enabled) -- 仅允许 true，存在该行表示已启用控制，不是可随意关闭的业务开关
);
