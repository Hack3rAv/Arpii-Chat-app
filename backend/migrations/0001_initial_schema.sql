-- Migration 0001: System Utility D1 Initial Schema
-- Strictly enforces a TWO-USER ONLY private messaging architecture.
-- Zero plaintext message bodies or unencrypted keys are stored in D1.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    slot_id INTEGER NOT NULL UNIQUE CHECK (slot_id IN (1, 2)),
    username TEXT NOT NULL UNIQUE,
    password_salt TEXT NOT NULL,
    server_salt TEXT NOT NULL,
    password_verifier TEXT NOT NULL,
    recovery_verifier TEXT NOT NULL,
    display_name TEXT NOT NULL,
    about TEXT NOT NULL DEFAULT '',
    avatar_r2_key TEXT,
    avatar_key_nonce TEXT,
    encryption_identity_id TEXT NOT NULL,
    last_seen_at INTEGER NOT NULL DEFAULT 0,
    privacy_show_last_seen INTEGER NOT NULL DEFAULT 1,
    privacy_show_online INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android',
    signal_device_id INTEGER NOT NULL DEFAULT 1,
    fcm_token TEXT,
    last_active_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER,
    UNIQUE(user_id, signal_device_id)
);

CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    last_used_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS login_attempts (
    rate_key TEXT PRIMARY KEY,
    failed_count INTEGER NOT NULL DEFAULT 0,
    first_attempt_at INTEGER NOT NULL,
    locked_until INTEGER
);

CREATE TABLE IF NOT EXISTS signal_identity_keys (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL DEFAULT 1,
    registration_id INTEGER NOT NULL,
    identity_key_b64 TEXT NOT NULL,
    signed_prekey_id INTEGER NOT NULL,
    signed_prekey_b64 TEXT NOT NULL,
    signed_prekey_signature_b64 TEXT NOT NULL,
    kyber_last_resort_id INTEGER NOT NULL,
    kyber_last_resort_b64 TEXT NOT NULL,
    kyber_last_resort_sig_b64 TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS signal_one_time_prekeys (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL DEFAULT 1,
    key_id INTEGER NOT NULL,
    public_key_b64 TEXT NOT NULL,
    is_used INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, device_id, key_id)
);

CREATE TABLE IF NOT EXISTS signal_kyber_prekeys (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id INTEGER NOT NULL DEFAULT 1,
    key_id INTEGER NOT NULL,
    public_key_b64 TEXT NOT NULL,
    signature_b64 TEXT NOT NULL,
    is_used INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    UNIQUE(user_id, device_id, key_id)
);

CREATE TABLE IF NOT EXISTS conversations (
    id TEXT PRIMARY KEY CHECK (id = 'primary_pair'),
    user_one_id TEXT NOT NULL REFERENCES users(id),
    user_two_id TEXT NOT NULL REFERENCES users(id),
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS encrypted_messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL DEFAULT 'primary_pair' REFERENCES conversations(id),
    sender_user_id TEXT NOT NULL REFERENCES users(id),
    sender_device_id INTEGER NOT NULL DEFAULT 1,
    recipient_user_id TEXT NOT NULL REFERENCES users(id),
    message_type TEXT NOT NULL DEFAULT 'signal_whisper',
    ciphertext_b64 TEXT NOT NULL,
    envelope_version INTEGER NOT NULL DEFAULT 1,
    reply_to_message_id TEXT,
    disappears_at INTEGER,
    is_edited INTEGER NOT NULL DEFAULT 0,
    is_deleted_for_everyone INTEGER NOT NULL DEFAULT 0,
    server_seq INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS message_receipts (
    message_id TEXT NOT NULL REFERENCES encrypted_messages(id) ON DELETE CASCADE,
    recipient_user_id TEXT NOT NULL REFERENCES users(id),
    status TEXT NOT NULL CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (message_id, recipient_user_id)
);

CREATE TABLE IF NOT EXISTS encrypted_media_objects (
    id TEXT PRIMARY KEY,
    uploader_user_id TEXT NOT NULL REFERENCES users(id),
    r2_object_key TEXT NOT NULL UNIQUE,
    encrypted_size_bytes INTEGER NOT NULL,
    sha256_ciphertext TEXT NOT NULL,
    mime_category TEXT NOT NULL CHECK (mime_category IN ('image', 'video', 'audio', 'voice', 'document', 'avatar')),
    upload_completed INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS call_logs (
    id TEXT PRIMARY KEY,
    caller_user_id TEXT NOT NULL REFERENCES users(id),
    callee_user_id TEXT NOT NULL REFERENCES users(id),
    call_type TEXT NOT NULL CHECK (call_type IN ('audio', 'video')),
    status TEXT NOT NULL CHECK (status IN ('INITIATED', 'RINGING', 'ANSWERED', 'REJECTED', 'MISSED', 'ENDED', 'FAILED')),
    started_at INTEGER NOT NULL,
    answered_at INTEGER,
    ended_at INTEGER
);

CREATE TABLE IF NOT EXISTS backup_manifests (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    drive_file_id TEXT NOT NULL,
    version_tag TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256_ciphertext TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
