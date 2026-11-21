-- im_account_tombstone：仅保留应用／账号标识和注销时间，拒绝迟到写入及旧凭证。
CREATE TABLE IF NOT EXISTS snap_flow.im_account_tombstone(
 app_id TEXT NOT NULL,
 user_id TEXT NOT NULL,
 deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
 PRIMARY KEY(app_id,user_id)
);
-- 全新数据库的当前结构，已有部署升级使用独立脚本，不重跑此文件。
-- 字段说明为源码注释，不写入数据库元数据。
-- im_user_state：用户可靠事件流、通知水位及未读计数，事务内与消息状态同步更新。
CREATE TABLE snap_flow.im_user_state(
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 cursor BIGINT NOT NULL DEFAULT 0, -- 该用户事件流最新游标，通知和单聊事件共用
 message_seq BIGINT NOT NULL DEFAULT 0, -- 通知收件箱最新分配序号，不含单聊会话序号
 read_seq BIGINT NOT NULL DEFAULT 0, -- 通知全部已读水位，单条已读另由消息状态维护
 retained_from BIGINT NOT NULL DEFAULT 0, -- 事件留存边界，游标早于此值时需重建快照
 notification_unread BIGINT NOT NULL DEFAULT 0 CHECK(notification_unread>=0), -- 尚未删除且未读的通知数量，不能用序号差计算
 direct_unread BIGINT NOT NULL DEFAULT 0 CHECK(direct_unread>=0), -- 全部单聊会话中来自对方的未读消息总数
 PRIMARY KEY(app_id,user_id)
);
-- im_message：用户通知收件箱，同时保存发送幂等信息和个人阅读、删除状态。
CREATE TABLE snap_flow.im_message(
 message_id TEXT PRIMARY KEY, -- 服务端生成的消息唯一标识
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 接收通知的用户标识
 seq BIGINT NOT NULL, -- 该用户通知收件箱内的消息序号，不是消息数量
 sender_id TEXT NOT NULL, -- 发送者标识，用于权限核验及幂等隔离
 client_key TEXT NOT NULL, -- 发送方幂等键，同一应用和发送方内唯一
 fingerprint TEXT NOT NULL, -- 请求内容指纹，阻止相同幂等键发送不同内容
 type TEXT NOT NULL, -- 通知类型标识，决定 payload 的解释方式
 payload TEXT NOT NULL, -- JSON 文本载荷，保留未知扩展字段
 created_at TEXT NOT NULL, -- UTC RFC 3339 时间文本
 is_read BOOLEAN NOT NULL DEFAULT FALSE, -- 单条通知已读标记，与 read_seq 共同判定未读
 is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 个人删除标记，过滤站内展示与待发送推送
 state_version BIGINT NOT NULL DEFAULT 0, -- 该通知最近一次状态变更版本，用于可靠同步归并
 UNIQUE(app_id,user_id,seq),
 UNIQUE(app_id,sender_id,client_key)
);
CREATE INDEX im_message_visible ON snap_flow.im_message(app_id,user_id,seq DESC) WHERE NOT is_deleted;
CREATE INDEX im_message_unread ON snap_flow.im_message(app_id,user_id,seq) WHERE NOT is_read AND NOT is_deleted;
-- im_event：按用户隔离的持久增量事件，供 HTTP 与 WebSocket 同步恢复。
CREATE TABLE snap_flow.im_event(
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 cursor BIGINT NOT NULL, -- 所属用户事件流内严格递增的游标，参与复合主键
 event_id TEXT NOT NULL, -- 事件唯一标识，与事件游标区分
 type TEXT NOT NULL, -- 事件类型标识，决定 data 的解释方式
 data TEXT NOT NULL, -- 事件 JSON 文本，包含消息、会话或状态变更
 created_at TEXT NOT NULL, -- UTC RFC 3339 时间文本
 PRIMARY KEY(app_id,user_id,cursor)
);
-- im_device：账号设备注册、推送偏好及已落盘事件确认，不存硬件身份。
CREATE TABLE snap_flow.im_device(
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 device_id TEXT NOT NULL, -- 客户端安装身份，不是 APNs token 或硬件标识
 token TEXT, -- 可空 APNs device token，轮换时更新，应用内非空值唯一
 muted BOOLEAN NOT NULL DEFAULT FALSE, -- 设备级静音偏好，禁止该设备系统推送
 preview BOOLEAN NOT NULL DEFAULT FALSE, -- 是否允许推送正文预览，默认关闭
 active BOOLEAN NOT NULL DEFAULT TRUE, -- 当前账号设备注册是否有效，下线或解绑后不可推送
 delivered_cursor BIGINT NOT NULL DEFAULT 0, -- 客户端已持久化并确认的用户事件游标，不代表用户已读
 updated_at BIGINT NOT NULL, -- 注册或偏好最近更新时间，Unix 秒
 PRIMARY KEY(app_id,user_id,device_id)
);
CREATE UNIQUE INDEX im_device_token ON snap_flow.im_device(app_id,token) WHERE token IS NOT NULL;
-- im_outbox：服务端按目标设备生成的 APNs 投递任务，不是业务宿主 Outbox。
CREATE TABLE snap_flow.im_outbox(
 id TEXT PRIMARY KEY, -- 推送任务唯一标识
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 目标用户标识
 message_id TEXT NOT NULL, -- 关联通知或单聊消息标识，投递前重新核验可投递性
 device_id TEXT NOT NULL, -- 客户端安装身份，不是 APNs token 或硬件标识
 attempts INTEGER NOT NULL DEFAULT 0, -- 已领取任务的次数，用于服务端重试预算
 next_at BIGINT NOT NULL, -- 下次允许领取时间，Unix 秒
 lease_until BIGINT NOT NULL DEFAULT 0, -- 领取租约到期时间，Unix 秒，过期后可被其他 Worker 接管
 lease_token TEXT, -- 本次领取标识，回写必须匹配以拒绝旧 Worker
 state TEXT NOT NULL DEFAULT 'pending', -- 任务状态 pending、done 或 dead，done 不代表设备已收到
 error TEXT -- 最后错误或投递结果说明，可记录 APNs 接受或跳过原因
);
CREATE INDEX im_outbox_pending ON snap_flow.im_outbox(state,next_at);
-- im_ticket：WebSocket 一次性短期票据，仅保存摘要并绑定账号和设备。
CREATE TABLE snap_flow.im_ticket(
 token_hash TEXT PRIMARY KEY, -- 票据摘要主键，不保存票据原文
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 device_id TEXT NOT NULL, -- 客户端安装身份，不是 APNs token 或硬件标识
 expires_at BIGINT NOT NULL, -- 票据自身过期时间，Unix 秒
 session_expires_at BIGINT NOT NULL -- 签发时用户会话的有效期上限，Unix 秒，用于终止连接
);
CREATE INDEX im_outbox_claim ON snap_flow.im_outbox(next_at,id) WHERE state='pending';
CREATE INDEX im_outbox_exhausted ON snap_flow.im_outbox(lease_until) WHERE state='pending' AND attempts>=10;
-- im_rate_limit：PostgreSQL 跨实例请求窗口计数，每个主体同一窗口原子累加。
CREATE TABLE snap_flow.im_rate_limit(
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 role TEXT NOT NULL, -- 鉴权角色，区分用户与服务调用
 subject TEXT NOT NULL, -- 被限流的认证主体标识
 window_start BIGINT NOT NULL, -- 计数窗口起始时间，Unix 秒
 hits INTEGER NOT NULL, -- 当前主体在该窗口累计的请求数
 PRIMARY KEY(app_id,role,subject)
);
CREATE INDEX im_rate_limit_expiry ON snap_flow.im_rate_limit(window_start);
CREATE INDEX im_ticket_expiry ON snap_flow.im_ticket(expires_at);
-- im_direct_conversation：双人单聊会话及双方独立已读水位、未读计数。
CREATE TABLE snap_flow.im_direct_conversation (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 conversation_id TEXT NOT NULL, -- 单聊会话标识
 user_low TEXT NOT NULL, -- 按标识排序后较小的一方，保证同一对用户只建一个会话
 user_high TEXT NOT NULL, -- 按标识排序后较大的一方，与 user_low 组成唯一关系
 message_seq BIGINT NOT NULL DEFAULT 0, -- 会话最新分配消息序号，不是可见消息数量
 revision BIGINT NOT NULL DEFAULT 1, -- 会话摘要版本，发消息、阅读或偏好变更时递增
 low_unread BIGINT NOT NULL DEFAULT 0 CHECK(low_unread>=0), -- user_low 尚未读且可见的对方消息数量
 high_unread BIGINT NOT NULL DEFAULT 0 CHECK(high_unread>=0), -- user_high 尚未读且可见的对方消息数量
 low_read BIGINT NOT NULL DEFAULT 0, -- user_low 阅读水位，用于向对方展示已读回执
 high_read BIGINT NOT NULL DEFAULT 0, -- user_high 阅读水位，用于向对方展示已读回执
 PRIMARY KEY(app_id,conversation_id),
 UNIQUE(app_id,user_low,user_high)
);
CREATE INDEX im_direct_low ON snap_flow.im_direct_conversation(app_id,user_low,conversation_id);
CREATE INDEX im_direct_high ON snap_flow.im_direct_conversation(app_id,user_high,conversation_id);
-- im_direct_message：会话消息事实和发送幂等记录，个人删除另存墓碑。
CREATE TABLE snap_flow.im_direct_message (
 message_id TEXT PRIMARY KEY, -- 服务端生成的消息唯一标识
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 conversation_id TEXT NOT NULL, -- 单聊会话标识
 seq BIGINT NOT NULL, -- 所属单聊会话内的消息序号，不是消息数量
 sender_id TEXT NOT NULL, -- 发送者标识，用于权限核验及幂等隔离
 client_key TEXT NOT NULL, -- 发送方幂等键，同一应用和发送方内唯一
 fingerprint TEXT NOT NULL, -- 请求内容指纹，阻止相同幂等键发送不同内容
 type TEXT NOT NULL, -- 单聊消息类型，当前包括 chat.text 和 chat.recalled
 payload TEXT NOT NULL, -- JSON 文本载荷，保留未知扩展字段
 created_at TEXT NOT NULL, -- UTC RFC 3339 时间文本
 FOREIGN KEY(app_id,conversation_id) REFERENCES snap_flow.im_direct_conversation(app_id,conversation_id),
 UNIQUE(app_id,conversation_id,seq),
 UNIQUE(app_id,sender_id,client_key)
);
-- 单聊消息状态版本，撤回时递增，用于阻止旧历史覆盖新状态。
ALTER TABLE snap_flow.im_direct_message ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;
-- im_direct_settings：用户级私信接收与在线可见范围，不承载业务关注关系。
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_settings (
 app_id TEXT NOT NULL, -- 应用隔离标识，与用户标识共同限定数据归属
 user_id TEXT NOT NULL, -- 所属用户标识
 inbound_scope TEXT NOT NULL DEFAULT 'mutual' CHECK(inbound_scope IN ('all','following','mutual')), -- 接收范围 all 为所有人，following 为本人关注的人，mutual 为互关
 read_receipts_visible BOOLEAN NOT NULL DEFAULT TRUE, -- 是否向对方公开本人已读位置；关闭不影响本人的未读和角标
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
INSERT INTO snap_flow.im_schema(version) VALUES(1);
