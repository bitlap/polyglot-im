ALTER TABLE im_user_state ADD COLUMN direct_unread BIGINT NOT NULL DEFAULT 0 CHECK(direct_unread>=0);
CREATE TABLE im_direct_conversation (
 app_id TEXT NOT NULL, conversation_id TEXT NOT NULL, user_low TEXT NOT NULL, user_high TEXT NOT NULL,
 message_seq BIGINT NOT NULL DEFAULT 0, revision BIGINT NOT NULL DEFAULT 1,
 low_unread BIGINT NOT NULL DEFAULT 0 CHECK(low_unread>=0), high_unread BIGINT NOT NULL DEFAULT 0 CHECK(high_unread>=0),
 low_read BIGINT NOT NULL DEFAULT 0, high_read BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(app_id,conversation_id), UNIQUE(app_id,user_low,user_high)
);
CREATE INDEX im_direct_low ON im_direct_conversation(app_id,user_low,conversation_id);
CREATE INDEX im_direct_high ON im_direct_conversation(app_id,user_high,conversation_id);
CREATE TABLE im_direct_message (
 message_id TEXT PRIMARY KEY, app_id TEXT NOT NULL, conversation_id TEXT NOT NULL, seq BIGINT NOT NULL,
 sender_id TEXT NOT NULL, client_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL,
 FOREIGN KEY(app_id,conversation_id) REFERENCES im_direct_conversation(app_id,conversation_id),
 UNIQUE(app_id,conversation_id,seq), UNIQUE(app_id,sender_id,client_key)
);
INSERT INTO im_schema_version(version) VALUES(3);
