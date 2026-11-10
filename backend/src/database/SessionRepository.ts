import { AuthContext, DeviceApprovalStatus, DeviceRow } from "../shared/types";
import { generateUuid } from "../shared/crypto";

export interface SessionWithDeviceView {
  sessionId: string;
  deviceId: string;
  deviceName: string;
  platform: string;
  signalDeviceId: number;
  approvalStatus: DeviceApprovalStatus;
  createdAt: number;
  lastUsedAt: number;
  expiresAt: number;
  revokedAt: number | null;
}

export interface PendingDeviceView {
  deviceId: string;
  deviceName: string;
  platform: string;
  signalDeviceId: number;
  approvalStatus: DeviceApprovalStatus;
  createdAt: number;
  lastActiveAt: number;
}

export type DeviceRegistrationOutcome =
  | { status: "APPROVED"; device: DeviceRow; newlyBootstrapped: boolean }
  | { status: "PENDING"; device: DeviceRow; isNewRequest: boolean }
  | { status: "REVOKED"; device: DeviceRow };

export class SessionRepository {
  constructor(private readonly db: D1Database) {}

  async registerOrCheckDevice(
    userId: string,
    requestedDeviceId: string | undefined,
    deviceName: string,
    platform: string,
    now: number
  ): Promise<DeviceRegistrationOutcome> {
    if (requestedDeviceId) {
      const existing = await this.db
        .prepare("SELECT * FROM devices WHERE id = ? AND user_id = ? LIMIT 1")
        .bind(requestedDeviceId, userId)
        .first<DeviceRow>();

      if (existing) {
        if (
          existing.revoked_at !== null ||
          existing.approval_status === "REVOKED" ||
          existing.approval_status === "REJECTED"
        ) {
          return { status: "REVOKED", device: existing };
        }

        await this.db
          .prepare(
            `UPDATE devices
             SET device_name = ?, platform = ?, last_active_at = ?
             WHERE id = ?`
          )
          .bind(deviceName, platform, now, existing.id)
          .run();

        const updated: DeviceRow = {
          ...existing,
          device_name: deviceName,
          platform,
          last_active_at: now,
        };

        if (existing.approval_status === "PENDING") {
          return { status: "PENDING", device: updated, isNewRequest: false };
        }

        return { status: "APPROVED", device: updated, newlyBootstrapped: false };
      }
    }

    // Count existing approved non-revoked devices for this user
    const approvedCountRow = await this.db
      .prepare(
        `SELECT COUNT(*) as cnt
         FROM devices
         WHERE user_id = ? AND approval_status = 'APPROVED' AND revoked_at IS NULL`
      )
      .bind(userId)
      .first<{ cnt: number }>();

    const hasApprovedDevice = (approvedCountRow?.cnt ?? 0) > 0;
    const approvalStatus: DeviceApprovalStatus = hasApprovedDevice ? "PENDING" : "APPROVED";

    // Reuse requestedDeviceId if not already taken globally in devices table
    let deviceId = generateUuid();
    if (requestedDeviceId) {
      const globalCollision = await this.db
        .prepare("SELECT id FROM devices WHERE id = ? LIMIT 1")
        .bind(requestedDeviceId)
        .first<{ id: string }>();
      if (!globalCollision) {
        deviceId = requestedDeviceId;
      }
    }

    const maxSig = await this.db
      .prepare("SELECT COALESCE(MAX(signal_device_id), 0) as maxId FROM devices WHERE user_id = ?")
      .bind(userId)
      .first<{ maxId: number }>();

    const signalDeviceId = (maxSig?.maxId ?? 0) + 1;
    const approvedAt = approvalStatus === "APPROVED" ? now : null;
    const approvedBy = approvalStatus === "APPROVED" ? deviceId : null;

    await this.db
      .prepare(
        `INSERT INTO devices (
           id, user_id, device_name, platform, signal_device_id, fcm_token,
           approval_status, approved_at, approved_by_device_id, last_active_at, created_at, revoked_at
         ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, NULL)`
      )
      .bind(deviceId, userId, deviceName, platform, signalDeviceId, approvalStatus, approvedAt, approvedBy, now, now)
      .run();

    const inserted: DeviceRow = {
      id: deviceId,
      user_id: userId,
      device_name: deviceName,
      platform,
      signal_device_id: signalDeviceId,
      approval_status: approvalStatus,
      approved_at: approvedAt,
      approved_by_device_id: approvedBy,
      last_active_at: now,
      created_at: now,
      revoked_at: null,
    };

    if (approvalStatus === "PENDING") {
      return { status: "PENDING", device: inserted, isNewRequest: true };
    }

    return { status: "APPROVED", device: inserted, newlyBootstrapped: true };
  }

  async approvePendingDevice(
    targetDeviceId: string,
    userId: string,
    approvingDeviceId: string,
    now: number
  ): Promise<boolean> {
    const res = await this.db
      .prepare(
        `UPDATE devices
         SET approval_status = 'APPROVED', approved_at = ?, approved_by_device_id = ?
         WHERE id = ? AND user_id = ? AND approval_status = 'PENDING' AND revoked_at IS NULL`
      )
      .bind(now, approvingDeviceId, targetDeviceId, userId)
      .run();
    return (res.meta?.changes ?? 0) > 0;
  }

  async rejectPendingDevice(
    targetDeviceId: string,
    userId: string,
    now: number
  ): Promise<boolean> {
    const res = await this.db
      .prepare(
        `UPDATE devices
         SET approval_status = 'REJECTED', revoked_at = ?
         WHERE id = ? AND user_id = ? AND approval_status = 'PENDING' AND revoked_at IS NULL`
      )
      .bind(now, targetDeviceId, userId)
      .run();
    return (res.meta?.changes ?? 0) > 0;
  }

  async listPendingDevices(userId: string): Promise<PendingDeviceView[]> {
    const res = await this.db
      .prepare(
        `SELECT id as deviceId, device_name as deviceName, platform,
                signal_device_id as signalDeviceId, approval_status as approvalStatus,
                created_at as createdAt, last_active_at as lastActiveAt
         FROM devices
         WHERE user_id = ? AND approval_status = 'PENDING' AND revoked_at IS NULL
         ORDER BY created_at DESC`
      )
      .bind(userId)
      .all<PendingDeviceView>();
    return res.results ?? [];
  }

  async createSession(
    userId: string,
    deviceId: string,
    tokenHash: string,
    expiresAt: number,
    now: number
  ): Promise<string> {
    const sessionId = generateUuid();
    await this.db
      .prepare(
        `INSERT INTO sessions (id, user_id, device_id, token_hash, expires_at, created_at, last_used_at, revoked_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, NULL)`
      )
      .bind(sessionId, userId, deviceId, tokenHash, expiresAt, now, now)
      .run();
    return sessionId;
  }

  async validateToken(
    tokenHash: string,
    deviceId: string | null,
    now: number
  ): Promise<AuthContext | null> {
    const row = await this.db
      .prepare(
        `SELECT s.id as session_id, s.user_id, s.device_id, s.expires_at, s.revoked_at as s_revoked,
                d.revoked_at as d_revoked, d.approval_status as d_approval_status, d.signal_device_id,
                u.slot_id, u.username
         FROM sessions s
         INNER JOIN devices d ON s.device_id = d.id
         INNER JOIN users u ON s.user_id = u.id
         WHERE s.token_hash = ?
         LIMIT 1`
      )
      .bind(tokenHash)
      .first<{
        session_id: string;
        user_id: string;
        device_id: string;
        expires_at: number;
        s_revoked: number | null;
        d_revoked: number | null;
        d_approval_status: string;
        signal_device_id: number;
        slot_id: number;
        username: string;
      }>();

    if (!row) return null;
    if (row.s_revoked !== null || row.d_revoked !== null) return null;
    if (row.d_approval_status !== "APPROVED") return null;
    if (row.expires_at <= now) return null;
    if (deviceId && row.device_id !== deviceId) return null;

    await this.db
      .prepare("UPDATE sessions SET last_used_at = ? WHERE id = ?")
      .bind(now, row.session_id)
      .run();

    return {
      userId: row.user_id,
      slotId: row.slot_id,
      username: row.username,
      deviceId: row.device_id,
      signalDeviceId: row.signal_device_id,
      sessionId: row.session_id,
    };
  }

  async revokeSession(sessionId: string, userId: string, now: number): Promise<boolean> {
    const res = await this.db
      .prepare(
        "UPDATE sessions SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL"
      )
      .bind(now, sessionId, userId)
      .run();
    return (res.meta?.changes ?? 0) > 0;
  }

  async revokeAllUserSessions(userId: string, now: number, exceptSessionId?: string): Promise<void> {
    if (exceptSessionId) {
      await this.db
        .prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND id != ? AND revoked_at IS NULL")
        .bind(now, userId, exceptSessionId)
        .run();
    } else {
      await this.db
        .prepare("UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
        .bind(now, userId)
        .run();
    }
  }

  async revokeDevice(deviceId: string, userId: string, now: number): Promise<boolean> {
    const devRes = await this.db
      .prepare(
        `UPDATE devices
         SET revoked_at = ?, approval_status = 'REVOKED'
         WHERE id = ? AND user_id = ? AND revoked_at IS NULL`
      )
      .bind(now, deviceId, userId)
      .run();

    await this.db
      .prepare("UPDATE sessions SET revoked_at = ? WHERE device_id = ? AND user_id = ? AND revoked_at IS NULL")
      .bind(now, deviceId, userId)
      .run();

    return (devRes.meta?.changes ?? 0) > 0;
  }

  async revokeAllUserDevices(userId: string, now: number): Promise<void> {
    await this.db
      .prepare(
        `UPDATE devices
         SET revoked_at = ?, approval_status = 'REVOKED'
         WHERE user_id = ? AND revoked_at IS NULL`
      )
      .bind(now, userId)
      .run();
  }

  async listActiveSessions(userId: string, now: number): Promise<SessionWithDeviceView[]> {
    const result = await this.db
      .prepare(
        `SELECT s.id as sessionId, s.device_id as deviceId, d.device_name as deviceName,
                d.platform as platform, d.signal_device_id as signalDeviceId,
                d.approval_status as approvalStatus,
                s.created_at as createdAt, s.last_used_at as lastUsedAt,
                s.expires_at as expiresAt, s.revoked_at as revokedAt
         FROM sessions s
         INNER JOIN devices d ON s.device_id = d.id
         WHERE s.user_id = ? AND s.revoked_at IS NULL AND d.revoked_at IS NULL
               AND d.approval_status = 'APPROVED' AND s.expires_at > ?
         ORDER BY s.last_used_at DESC`
      )
      .bind(userId, now)
      .all<SessionWithDeviceView>();
    return result.results ?? [];
  }
}
