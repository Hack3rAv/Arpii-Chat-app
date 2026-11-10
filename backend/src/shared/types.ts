export interface Env {
  DB: D1Database;
  MEDIA_BUCKET: R2Bucket;
  CHAT_COORDINATOR: DurableObjectNamespace;
  ENVIRONMENT: string;
  ALLOWED_ORIGIN: string;
  MAX_UPLOAD_BYTES: string;
  RETENTION_DAYS: string;
  AUTH_PEPPER: string;
  CF_TURN_KEY_ID?: string;
  CF_TURN_API_TOKEN?: string;
}

export interface UserRow {
  id: string;
  slot_id: number;
  username: string;
  password_salt: string;
  server_salt: string;
  password_verifier: string;
  recovery_verifier: string;
  display_name: string;
  about: string;
  avatar_r2_key: string | null;
  avatar_key_nonce: string | null;
  encryption_identity_id: string;
  last_seen_at: number;
  privacy_show_last_seen: number;
  privacy_show_online: number;
  created_at: number;
  updated_at: number;
}

export type DeviceApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "REVOKED";

export interface DeviceRow {
  id: string;
  user_id: string;
  device_name: string;
  platform: string;
  signal_device_id: number;
  approval_status: DeviceApprovalStatus;
  approved_at: number | null;
  approved_by_device_id: string | null;
  last_active_at: number;
  created_at: number;
  revoked_at: number | null;
}

export interface SessionRow {
  id: string;
  user_id: string;
  device_id: string;
  token_hash: string;
  expires_at: number;
  created_at: number;
  last_used_at: number;
  revoked_at: number | null;
}

export interface LoginAttemptRow {
  rate_key: string;
  failed_count: number;
  first_attempt_at: number;
  locked_until: number | null;
}

export type AuditSeverity = "INFO" | "WARN" | "SECURITY";

export interface AuditEventRow {
  id: string;
  actor_user_id: string | null;
  target_device_id: string | null;
  event_type: string;
  severity: AuditSeverity;
  ip_hash: string | null;
  metadata_json: string;
  created_at: number;
}

export interface AuthContext {
  userId: string;
  slotId: number;
  username: string;
  deviceId: string;
  signalDeviceId: number;
  sessionId: string;
}
