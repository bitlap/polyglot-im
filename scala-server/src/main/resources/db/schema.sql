CREATE TABLE snap_flow.im_user_state(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,cursor BIGINT NOT NULL DEFAULT 0,
 message_seq BIGINT NOT NULL DEFAULT 0,read_seq BIGINT NOT NULL DEFAULT 0,
 retained_from BIGINT NOT NULL DEFAULT 0,
 notification_unread BIGINT NOT NULL DEFAULT 0 CHECK(notification_unread>=0),
 direct_unread BIGINT NOT NULL DEFAULT 0 CHECK(direct_unread>=0),PRIMARY KEY(app_id,user_id));
CREATE TABLE snap_flow.im_message(
 message_id TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,seq BIGINT NOT NULL,
 sender_id TEXT NOT NULL,client_key TEXT NOT NULL,fingerprint TEXT NOT NULL,
 type TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,
 is_read BOOLEAN NOT NULL DEFAULT FALSE,is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
 state_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(app_id,user_id,seq),UNIQUE(app_id,sender_id,client_key));
CREATE INDEX im_message_visible ON snap_flow.im_message(app_id,user_id,seq DESC) WHERE NOT is_deleted;
CREATE INDEX im_message_unread ON snap_flow.im_message(app_id,user_id,seq) WHERE NOT is_read AND NOT is_deleted;
CREATE TABLE snap_flow.im_event(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,cursor BIGINT NOT NULL,event_id TEXT NOT NULL,
 type TEXT NOT NULL,data TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(app_id,user_id,cursor));
CREATE TABLE snap_flow.im_device(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT NOT NULL,token TEXT,
 muted BOOLEAN NOT NULL DEFAULT FALSE,preview BOOLEAN NOT NULL DEFAULT FALSE,
 active BOOLEAN NOT NULL DEFAULT TRUE,delivered_cursor BIGINT NOT NULL DEFAULT 0,
 updated_at BIGINT NOT NULL,PRIMARY KEY(app_id,user_id,device_id));
CREATE UNIQUE INDEX im_device_token ON snap_flow.im_device(app_id,token) WHERE token IS NOT NULL;
CREATE TABLE snap_flow.im_outbox(
 id TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,message_id TEXT NOT NULL,
 device_id TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL,
 lease_until BIGINT NOT NULL DEFAULT 0,lease_token TEXT,state TEXT NOT NULL DEFAULT 'pending',error TEXT);
CREATE INDEX im_outbox_pending ON snap_flow.im_outbox(state,next_at);
CREATE TABLE snap_flow.im_ticket(
 token_hash TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT NOT NULL,
 expires_at BIGINT NOT NULL,session_expires_at BIGINT NOT NULL);
CREATE INDEX im_outbox_claim ON snap_flow.im_outbox(next_at,id) WHERE state='pending';
CREATE INDEX im_outbox_exhausted ON snap_flow.im_outbox(lease_until) WHERE state='pending' AND attempts>=10;
CREATE TABLE snap_flow.im_rate_limit(
 app_id TEXT NOT NULL,role TEXT NOT NULL,subject TEXT NOT NULL,window_start BIGINT NOT NULL,
 hits INTEGER NOT NULL,PRIMARY KEY(app_id,role,subject));
CREATE INDEX im_rate_limit_expiry ON snap_flow.im_rate_limit(window_start);
CREATE INDEX im_ticket_expiry ON snap_flow.im_ticket(expires_at);
CREATE TABLE snap_flow.im_direct_conversation (
 app_id TEXT NOT NULL, conversation_id TEXT NOT NULL, user_low TEXT NOT NULL, user_high TEXT NOT NULL,
 message_seq BIGINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 1,
 low_unread BIGINT NOT NULL DEFAULT 0 CHECK(low_unread>=0), high_unread BIGINT NOT NULL DEFAULT 0 CHECK(high_unread>=0),
 low_read BIGINT NOT NULL DEFAULT 0, high_read BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(app_id,conversation_id), UNIQUE(app_id,user_low,user_high)
);
CREATE INDEX im_direct_low ON snap_flow.im_direct_conversation(app_id,user_low,conversation_id);
CREATE INDEX im_direct_high ON snap_flow.im_direct_conversation(app_id,user_high,conversation_id);
CREATE TABLE snap_flow.im_direct_message (
 message_id TEXT PRIMARY KEY, app_id TEXT NOT NULL, conversation_id TEXT NOT NULL, seq BIGINT NOT NULL,
 sender_id TEXT NOT NULL, client_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL,
 FOREIGN KEY(app_id,conversation_id) REFERENCES snap_flow.im_direct_conversation(app_id,conversation_id),
 UNIQUE(app_id,conversation_id,seq), UNIQUE(app_id,sender_id,client_key)
);
INSERT INTO snap_flow.im_schema(version) VALUES(1);
