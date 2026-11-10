-- Migration 0002: Covering Indexes and Two-User Hard Enforcement Triggers

CREATE TRIGGER IF NOT EXISTS enforce_two_users_only
BEFORE INSERT ON users
WHEN (SELECT COUNT(*) FROM users) >= 2
BEGIN
    SELECT RAISE(ABORT, 'SYSTEM_UTILITY_PAIR_LIMIT: Maximum of 2 authorized users permitted.');
END;

CREATE INDEX IF NOT EXISTS idx_sessions_user_active
    ON sessions(user_id, revoked_at, expires_at);

CREATE INDEX IF NOT EXISTS idx_devices_user_active
    ON devices(user_id, revoked_at);

CREATE INDEX IF NOT EXISTS idx_messages_recipient_seq
    ON encrypted_messages(recipient_user_id, server_seq);

CREATE INDEX IF NOT EXISTS idx_messages_conv_seq
    ON encrypted_messages(conversation_id, server_seq);

CREATE INDEX IF NOT EXISTS idx_messages_disappears
    ON encrypted_messages(disappears_at)
    WHERE disappears_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_otp_unused
    ON signal_one_time_prekeys(user_id, device_id, is_used, key_id);

CREATE INDEX IF NOT EXISTS idx_kyber_unused
    ON signal_kyber_prekeys(user_id, device_id, is_used, key_id);

CREATE INDEX IF NOT EXISTS idx_call_logs_started
    ON call_logs(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_backup_user_created
    ON backup_manifests(user_id, created_at DESC);
