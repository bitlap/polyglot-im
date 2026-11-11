CREATE TABLE IF NOT EXISTS im_schema_version(version INTEGER PRIMARY KEY);
CREATE TABLE IF NOT EXISTS im_user_state(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,cursor BIGINT NOT NULL DEFAULT 0,
 message_seq BIGINT NOT NULL DEFAULT 0,read_seq BIGINT NOT NULL DEFAULT 0,
 retained_from BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(app_id,user_id));
CREATE TABLE IF NOT EXISTS im_message(
 message_id TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,seq BIGINT NOT NULL,
 sender_id TEXT NOT NULL,client_key TEXT NOT NULL,fingerprint TEXT NOT NULL,
 type TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,
 UNIQUE(app_id,user_id,seq),UNIQUE(app_id,sender_id,client_key));
CREATE INDEX IF NOT EXISTS im_message_history ON im_message(app_id,user_id,seq DESC);
CREATE TABLE IF NOT EXISTS im_event(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,cursor BIGINT NOT NULL,event_id TEXT NOT NULL,
 type TEXT NOT NULL,data TEXT NOT NULL,created_at TEXT NOT NULL,PRIMARY KEY(app_id,user_id,cursor));
CREATE TABLE IF NOT EXISTS im_device(
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT NOT NULL,token TEXT,
 muted BOOLEAN NOT NULL DEFAULT FALSE,preview BOOLEAN NOT NULL DEFAULT FALSE,
 active BOOLEAN NOT NULL DEFAULT TRUE,delivered_cursor BIGINT NOT NULL DEFAULT 0,
 updated_at BIGINT NOT NULL,PRIMARY KEY(app_id,user_id,device_id));
CREATE UNIQUE INDEX IF NOT EXISTS im_device_token ON im_device(app_id,token) WHERE token IS NOT NULL;
CREATE TABLE IF NOT EXISTS im_outbox(
 id TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,message_id TEXT NOT NULL,
 device_id TEXT NOT NULL,attempts INTEGER NOT NULL DEFAULT 0,next_at BIGINT NOT NULL,
 lease_until BIGINT NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT 'pending',error TEXT);
CREATE INDEX IF NOT EXISTS im_outbox_pending ON im_outbox(state,next_at);
CREATE TABLE IF NOT EXISTS im_ticket(
 token_hash TEXT PRIMARY KEY,app_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT NOT NULL,
 expires_at BIGINT NOT NULL,session_expires_at BIGINT NOT NULL);
INSERT INTO im_schema_version(version) VALUES(1) ON CONFLICT DO NOTHING;
