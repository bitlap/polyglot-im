DROP INDEX IF EXISTS im_message_history;
ALTER TABLE im_outbox ADD COLUMN IF NOT EXISTS lease_token TEXT;
CREATE INDEX IF NOT EXISTS im_outbox_claim ON im_outbox(next_at,id) WHERE state='pending';
CREATE INDEX IF NOT EXISTS im_outbox_exhausted ON im_outbox(lease_until) WHERE state='pending' AND attempts>=10;
CREATE TABLE IF NOT EXISTS im_rate_limit(
 app_id TEXT NOT NULL,role TEXT NOT NULL,subject TEXT NOT NULL,window_start BIGINT NOT NULL,
 hits INTEGER NOT NULL,PRIMARY KEY(app_id,role,subject));
CREATE INDEX IF NOT EXISTS im_rate_limit_expiry ON im_rate_limit(window_start);
CREATE INDEX IF NOT EXISTS im_ticket_expiry ON im_ticket(expires_at);
INSERT INTO im_schema_version(version) VALUES(2) ON CONFLICT DO NOTHING;
