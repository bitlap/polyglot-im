ALTER TABLE snap_flow.im_direct_message ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_settings (
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,inbound_scope TEXT NOT NULL DEFAULT 'mutual' CHECK(inbound_scope IN ('all','following','mutual')),
 online_visibility TEXT NOT NULL DEFAULT 'mutual' CHECK(online_visibility IN ('mutual','off')),PRIMARY KEY(app_id,user_id));
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_preference (
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,peer_id TEXT NOT NULL,blocked BOOLEAN NOT NULL DEFAULT FALSE,
 muted BOOLEAN NOT NULL DEFAULT FALSE,pinned BOOLEAN NOT NULL DEFAULT FALSE,
 hidden_through BIGINT NOT NULL DEFAULT -1,clear_seq BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(app_id,user_id,peer_id),CHECK(user_id<>peer_id));
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_deleted (
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,conversation_id TEXT NOT NULL,message_id TEXT NOT NULL,state_version BIGINT NOT NULL,
 PRIMARY KEY(app_id,user_id,message_id),
 FOREIGN KEY(app_id,conversation_id) REFERENCES snap_flow.im_direct_conversation(app_id,conversation_id));
CREATE TABLE IF NOT EXISTS snap_flow.im_presence (
 app_id TEXT NOT NULL,user_id TEXT NOT NULL,device_id TEXT NOT NULL,lease_until BIGINT NOT NULL,
 PRIMARY KEY(app_id,user_id,device_id),
 FOREIGN KEY(app_id,user_id,device_id) REFERENCES snap_flow.im_device(app_id,user_id,device_id));
CREATE INDEX IF NOT EXISTS im_presence_expiry ON snap_flow.im_presence(lease_until);
CREATE TABLE IF NOT EXISTS snap_flow.im_direct_activation(enabled BOOLEAN PRIMARY KEY CHECK(enabled));
