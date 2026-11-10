-- Migration 0003: Owner-Approved Device Enrollment, Short-Lived Upload Grants, and Security Audit Events
-- Additive, non-destructive migration preserving existing accounts and tables.

ALTER TABLE devices ADD COLUMN approval_status TEXT NOT NULL DEFAULT 'APPROVED'
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'));

ALTER TABLE devices ADD COLUMN approved_at INTEGER;

ALTER TABLE devices ADD COLUMN approved_by_device_id TEXT;

ALTER TABLE encrypted_media_objects ADD COLUMN upload_expires_at INTEGER;

CREATE TABLE IF NOT EXISTS audit_events (
    id TEXT PRIMARY KEY,
    actor_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
    target_device_id TEXT,
    event_type TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARN', 'SECURITY')),
    ip_hash TEXT,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_devices_user_approval
    ON devices(user_id, approval_status, revoked_at);

CREATE INDEX IF NOT EXISTS idx_audit_actor_created
    ON audit_events(actor_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_type_created
    ON audit_events(event_type, created_at DESC);
