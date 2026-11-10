import { AuthContext, Env, UserRow } from "../shared/types";
import { UserRepository } from "../database/UserRepository";
import { PendingDeviceView, SessionRepository, SessionWithDeviceView } from "../database/SessionRepository";
import { RateLimitRepository } from "../database/RateLimitRepository";
import { AuditEventView, AuditRepository } from "../database/AuditRepository";
import {
  constantTimeEqualHex,
  derivePseudoSalt,
  deriveServerVerifier,
  randomHex,
  sha256Hex,
} from "../shared/crypto";
import { SafeLogger } from "../shared/logger";

const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

export interface AuthServiceError {
  status: number;
  code: string;
  message: string;
  retryAfterSeconds?: number;
  pendingDeviceId?: string;
}

export type ServiceResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: AuthServiceError };

export interface LoginResult {
  token: string;
  sessionId: string;
  expiresAt: number;
  deviceId: string;
  signalDeviceId: number;
  selfUser: PublicUserProfile;
  partnerUser: PublicUserProfile | null;
}

export interface PublicUserProfile {
  id: string;
  slotId: number;
  username: string;
  displayName: string;
  about: string;
  encryptionIdentityId: string;
  avatarR2Key: string | null;
  avatarKeyBase64: string | null;
  lastSeenAt: number | null;
  privacyShowLastSeen: boolean;
  privacyShowOnline: boolean;
}

export function toPublicProfile(user: UserRow, includePrivatePrefs = false): PublicUserProfile {
  return {
    id: user.id,
    slotId: user.slot_id,
    username: user.username,
    displayName: user.display_name,
    about: user.about,
    encryptionIdentityId: user.encryption_identity_id,
    avatarR2Key: user.avatar_r2_key,
    avatarKeyBase64: user.avatar_key_nonce,
    lastSeenAt: includePrivatePrefs || user.privacy_show_last_seen === 1 ? user.last_seen_at : null,
    privacyShowLastSeen: user.privacy_show_last_seen === 1,
    privacyShowOnline: user.privacy_show_online === 1,
  };
}

export class AuthService {
  private readonly userRepo: UserRepository;
  private readonly sessionRepo: SessionRepository;
  private readonly rateLimitRepo: RateLimitRepository;
  private readonly auditRepo: AuditRepository;

  constructor(private readonly env: Env) {
    this.userRepo = new UserRepository(env.DB);
    this.sessionRepo = new SessionRepository(env.DB);
    this.rateLimitRepo = new RateLimitRepository(env.DB);
    this.auditRepo = new AuditRepository(env.DB, this.getPepper());
  }

  private getPepper(): string {
    return this.env.AUTH_PEPPER || "ephemeral_fallback_set_AUTH_PEPPER_secret";
  }

  async getLoginSalt(
    username: string,
    clientIp: string,
    now = Date.now()
  ): Promise<ServiceResult<{ saltHex: string; kdf: { algorithm: string; memoryKiB: number; iterations: number; parallelism: number } }>> {
    const rateKeys = [`ip:${clientIp}`];
    const rateStatus = await this.rateLimitRepo.checkKeys(rateKeys, now);
    if (rateStatus.blocked) {
      return {
        ok: false,
        error: {
          status: 429,
          code: "RATE_LIMITED",
          message: `Too many authentication attempts. Try again in ${rateStatus.retryAfterSeconds}s.`,
          retryAfterSeconds: rateStatus.retryAfterSeconds,
        },
      };
    }

    const user = await this.userRepo.findByUsername(username);
    const saltHex = user ? user.password_salt : await derivePseudoSalt(this.getPepper(), username);

    return {
      ok: true,
      data: {
        saltHex,
        kdf: {
          algorithm: "argon2id",
          memoryKiB: 65536,
          iterations: 3,
          parallelism: 1,
        },
      },
    };
  }

  async login(
    input: {
      username: string;
      clientDigestHex: string;
      deviceName: string;
      deviceId?: string;
      platform: string;
    },
    clientIp: string,
    now = Date.now()
  ): Promise<ServiceResult<LoginResult>> {
    const normalizedUser = input.username.trim().toLowerCase();
    const rateKeys = [`ip:${clientIp}`, `user:${normalizedUser}`];

    const rateStatus = await this.rateLimitRepo.checkKeys(rateKeys, now);
    if (rateStatus.blocked) {
      SafeLogger.warn("auth_login_rate_limited", { username: normalizedUser, ip: clientIp });
      return {
        ok: false,
        error: {
          status: 429,
          code: "RATE_LIMITED",
          message: `Account temporarily locked due to repeated failed attempts. Retry in ${rateStatus.retryAfterSeconds}s.`,
          retryAfterSeconds: rateStatus.retryAfterSeconds,
        },
      };
    }

    const user = await this.userRepo.findByUsername(normalizedUser);
    if (!user) {
      // Constant-time defense: perform same PBKDF2 computational cost even when user does not exist
      const fakeSalt = await derivePseudoSalt(this.getPepper(), normalizedUser);
      await deriveServerVerifier(input.clientDigestHex, fakeSalt);
      const afterFailure = await this.rateLimitRepo.recordFailure(rateKeys, now);
      SafeLogger.warn("auth_login_unknown_user", { username: normalizedUser });

      if (afterFailure.blocked) {
        return {
          ok: false,
          error: {
            status: 429,
            code: "RATE_LIMITED",
            message: `Too many failed attempts. Retry in ${afterFailure.retryAfterSeconds}s.`,
            retryAfterSeconds: afterFailure.retryAfterSeconds,
          },
        };
      }
      return {
        ok: false,
        error: { status: 401, code: "INVALID_CREDENTIALS", message: "Invalid username or password." },
      };
    }

    const computedVerifier = await deriveServerVerifier(input.clientDigestHex, user.server_salt);
    const valid = constantTimeEqualHex(computedVerifier, user.password_verifier);

    if (!valid) {
      const afterFailure = await this.rateLimitRepo.recordFailure(rateKeys, now);
      await this.auditRepo.recordEvent({
        actorUserId: user.id,
        eventType: afterFailure.blocked ? "AUTH_LOGIN_RATE_LIMITED" : "AUTH_LOGIN_FAILED",
        severity: "SECURITY",
        clientIp,
        metadata: { deviceName: input.deviceName, platform: input.platform },
        now,
      });
      SafeLogger.warn("auth_login_invalid_password", { userId: user.id });

      if (afterFailure.blocked) {
        return {
          ok: false,
          error: {
            status: 429,
            code: "RATE_LIMITED",
            message: `Too many failed attempts. Retry in ${afterFailure.retryAfterSeconds}s.`,
            retryAfterSeconds: afterFailure.retryAfterSeconds,
          },
        };
      }
      return {
        ok: false,
        error: { status: 401, code: "INVALID_CREDENTIALS", message: "Invalid username or password." },
      };
    }

    await this.rateLimitRepo.clearKeys(rateKeys);

    const registration = await this.sessionRepo.registerOrCheckDevice(
      user.id,
      input.deviceId,
      input.deviceName,
      input.platform,
      now
    );

    if (registration.status === "REVOKED") {
      await this.auditRepo.recordEvent({
        actorUserId: user.id,
        targetDeviceId: registration.device.id,
        eventType: "DEVICE_LOGIN_BLOCKED_REVOKED",
        severity: "SECURITY",
        clientIp,
        metadata: { deviceName: input.deviceName, approvalStatus: registration.device.approval_status },
        now,
      });
      return {
        ok: false,
        error: {
          status: 403,
          code: "DEVICE_REVOKED",
          message: "This device has been revoked or rejected by the account owner. Rotate device identity to request fresh enrollment.",
        },
      };
    }

    if (registration.status === "PENDING") {
      if (registration.isNewRequest) {
        await this.auditRepo.recordEvent({
          actorUserId: user.id,
          targetDeviceId: registration.device.id,
          eventType: "DEVICE_ENROLLMENT_PENDING",
          severity: "SECURITY",
          clientIp,
          metadata: { deviceName: input.deviceName, platform: input.platform },
          now,
        });
      }
      SafeLogger.info("auth_device_approval_required", {
        userId: user.id,
        pendingDeviceId: registration.device.id,
      });
      return {
        ok: false,
        error: {
          status: 403,
          code: "DEVICE_APPROVAL_REQUIRED",
          message:
            "This device is waiting for owner approval from an already-signed-in device under Active Devices & Sessions.",
          pendingDeviceId: registration.device.id,
        },
      };
    }

    const device = registration.device;
    const rawToken = randomHex(32);
    const tokenHash = await sha256Hex(rawToken);
    const expiresAt = now + SESSION_TTL_MS;
    const sessionId = await this.sessionRepo.createSession(user.id, device.id, tokenHash, expiresAt, now);

    await this.userRepo.touchLastSeen(user.id, now);
    const partner = await this.userRepo.findPartner(user.id);

    await this.auditRepo.recordEvent({
      actorUserId: user.id,
      targetDeviceId: device.id,
      eventType: registration.newlyBootstrapped ? "DEVICE_BOOTSTRAP_APPROVED" : "AUTH_LOGIN_SUCCESS",
      severity: "INFO",
      clientIp,
      metadata: { deviceName: device.device_name, sessionId },
      now,
    });

    SafeLogger.info("auth_login_success", { userId: user.id, deviceId: device.id, sessionId });

    return {
      ok: true,
      data: {
        token: rawToken,
        sessionId,
        expiresAt,
        deviceId: device.id,
        signalDeviceId: device.signal_device_id,
        selfUser: toPublicProfile(user, true),
        partnerUser: partner ? toPublicProfile(partner, false) : null,
      },
    };
  }

  async logout(context: AuthContext, clientIp?: string, now = Date.now()): Promise<void> {
    await this.sessionRepo.revokeSession(context.sessionId, context.userId, now);
    await this.notifySessionRevoked(context.sessionId, context.deviceId);
    await this.auditRepo.recordEvent({
      actorUserId: context.userId,
      targetDeviceId: context.deviceId,
      eventType: "AUTH_LOGOUT",
      severity: "INFO",
      clientIp,
      metadata: { sessionId: context.sessionId },
      now,
    });
    SafeLogger.info("auth_logout", { userId: context.userId, sessionId: context.sessionId });
  }

  async changePassword(
    context: AuthContext,
    input: { currentClientDigestHex: string; newSaltHex: string; newClientDigestHex: string },
    clientIp?: string,
    now = Date.now()
  ): Promise<ServiceResult<{ revokedOtherSessions: boolean }>> {
    const user = await this.userRepo.findById(context.userId);
    if (!user) {
      return { ok: false, error: { status: 404, code: "NOT_FOUND", message: "Account not found." } };
    }

    const currentVerifier = await deriveServerVerifier(input.currentClientDigestHex, user.server_salt);
    if (!constantTimeEqualHex(currentVerifier, user.password_verifier)) {
      SafeLogger.warn("auth_change_password_mismatch", { userId: user.id });
      await this.auditRepo.recordEvent({
        actorUserId: user.id,
        targetDeviceId: context.deviceId,
        eventType: "AUTH_PASSWORD_CHANGE_FAILED",
        severity: "SECURITY",
        clientIp,
        now,
      });
      return {
        ok: false,
        error: { status: 401, code: "INVALID_CREDENTIALS", message: "Current password is incorrect." },
      };
    }

    const newServerSalt = randomHex(16);
    const newVerifier = await deriveServerVerifier(input.newClientDigestHex, newServerSalt);
    await this.userRepo.updatePasswordVerifier(user.id, input.newSaltHex, newServerSalt, newVerifier, now);

    // Revoke all other sessions on password rotation; preserve current device's session
    await this.sessionRepo.revokeAllUserSessions(user.id, now, context.sessionId);
    await this.auditRepo.recordEvent({
      actorUserId: user.id,
      targetDeviceId: context.deviceId,
      eventType: "AUTH_PASSWORD_CHANGED",
      severity: "SECURITY",
      clientIp,
      now,
    });
    SafeLogger.info("auth_password_changed", { userId: user.id });

    return { ok: true, data: { revokedOtherSessions: true } };
  }

  async recoverAccount(
    input: {
      username: string;
      recoveryDigestHex: string;
      newSaltHex: string;
      newClientDigestHex: string;
    },
    clientIp: string,
    now = Date.now()
  ): Promise<ServiceResult<{ recovered: boolean }>> {
    const normalizedUser = input.username.trim().toLowerCase();
    const rateKeys = [`recover:ip:${clientIp}`, `recover:user:${normalizedUser}`];
    const rateStatus = await this.rateLimitRepo.checkKeys(rateKeys, now);
    if (rateStatus.blocked) {
      return {
        ok: false,
        error: {
          status: 429,
          code: "RATE_LIMITED",
          message: `Too many recovery attempts. Retry in ${rateStatus.retryAfterSeconds}s.`,
          retryAfterSeconds: rateStatus.retryAfterSeconds,
        },
      };
    }

    const user = await this.userRepo.findByUsername(normalizedUser);
    if (!user) {
      const fakeSalt = await derivePseudoSalt(this.getPepper(), normalizedUser);
      await deriveServerVerifier(input.recoveryDigestHex, fakeSalt);
      await this.rateLimitRepo.recordFailure(rateKeys, now);
      return {
        ok: false,
        error: { status: 401, code: "INVALID_RECOVERY", message: "Invalid username or recovery code." },
      };
    }

    const computedRecovery = await deriveServerVerifier(input.recoveryDigestHex, user.server_salt);
    if (!constantTimeEqualHex(computedRecovery, user.recovery_verifier)) {
      await this.rateLimitRepo.recordFailure(rateKeys, now);
      await this.auditRepo.recordEvent({
        actorUserId: user.id,
        eventType: "AUTH_RECOVER_FAILED",
        severity: "SECURITY",
        clientIp,
        now,
      });
      SafeLogger.warn("auth_recover_invalid_code", { userId: user.id });
      return {
        ok: false,
        error: { status: 401, code: "INVALID_RECOVERY", message: "Invalid username or recovery code." },
      };
    }

    await this.rateLimitRepo.clearKeys(rateKeys);
    const newVerifier = await deriveServerVerifier(input.newClientDigestHex, user.server_salt);
    await this.userRepo.updatePasswordVerifier(user.id, input.newSaltHex, user.server_salt, newVerifier, now);

    // Revoke all existing sessions and previously approved devices cleanly after emergency recovery
    await this.sessionRepo.revokeAllUserSessions(user.id, now);
    await this.sessionRepo.revokeAllUserDevices(user.id, now);
    await this.auditRepo.recordEvent({
      actorUserId: user.id,
      eventType: "AUTH_RECOVERED",
      severity: "SECURITY",
      clientIp,
      now,
    });
    SafeLogger.info("auth_account_recovered", { userId: user.id });

    return { ok: true, data: { recovered: true } };
  }

  async listSessionsAndPendingDevices(
    context: AuthContext,
    now = Date.now()
  ): Promise<{ sessions: SessionWithDeviceView[]; pendingDevices: PendingDeviceView[] }> {
    const [sessions, pendingDevices] = await Promise.all([
      this.sessionRepo.listActiveSessions(context.userId, now),
      this.sessionRepo.listPendingDevices(context.userId),
    ]);
    return { sessions, pendingDevices };
  }

  async approveDevice(
    context: AuthContext,
    targetDeviceId: string,
    clientIp?: string,
    now = Date.now()
  ): Promise<boolean> {
    const approved = await this.sessionRepo.approvePendingDevice(
      targetDeviceId,
      context.userId,
      context.deviceId,
      now
    );
    if (approved) {
      await this.auditRepo.recordEvent({
        actorUserId: context.userId,
        targetDeviceId,
        eventType: "DEVICE_APPROVED",
        severity: "SECURITY",
        clientIp,
        metadata: { approvedByDeviceId: context.deviceId },
        now,
      });
      SafeLogger.info("auth_device_approved", {
        userId: context.userId,
        targetDeviceId,
        approvedByDeviceId: context.deviceId,
      });
    }
    return approved;
  }

  async rejectDevice(
    context: AuthContext,
    targetDeviceId: string,
    clientIp?: string,
    now = Date.now()
  ): Promise<boolean> {
    const rejected = await this.sessionRepo.rejectPendingDevice(targetDeviceId, context.userId, now);
    if (rejected) {
      await this.auditRepo.recordEvent({
        actorUserId: context.userId,
        targetDeviceId,
        eventType: "DEVICE_REJECTED",
        severity: "SECURITY",
        clientIp,
        metadata: { rejectedByDeviceId: context.deviceId },
        now,
      });
      SafeLogger.info("auth_device_rejected", {
        userId: context.userId,
        targetDeviceId,
        rejectedByDeviceId: context.deviceId,
      });
    }
    return rejected;
  }

  async revokeSession(context: AuthContext, targetSessionId: string, clientIp?: string, now = Date.now()): Promise<boolean> {
    const revoked = await this.sessionRepo.revokeSession(targetSessionId, context.userId, now);
    if (revoked) {
      await this.notifySessionRevoked(targetSessionId, undefined);
      await this.auditRepo.recordEvent({
        actorUserId: context.userId,
        eventType: "SESSION_REVOKED",
        severity: "SECURITY",
        clientIp,
        metadata: { targetSessionId },
        now,
      });
      SafeLogger.info("auth_session_revoked", { userId: context.userId, targetSessionId });
    }
    return revoked;
  }

  async revokeDevice(context: AuthContext, targetDeviceId: string, clientIp?: string, now = Date.now()): Promise<boolean> {
    const revoked = await this.sessionRepo.revokeDevice(targetDeviceId, context.userId, now);
    if (revoked) {
      await this.notifySessionRevoked(undefined, targetDeviceId);
      await this.auditRepo.recordEvent({
        actorUserId: context.userId,
        targetDeviceId,
        eventType: "DEVICE_REVOKED",
        severity: "SECURITY",
        clientIp,
        metadata: { revokedByDeviceId: context.deviceId },
        now,
      });
      SafeLogger.info("auth_device_revoked", { userId: context.userId, targetDeviceId });
    }
    return revoked;
  }

  async listAuditEvents(context: AuthContext): Promise<AuditEventView[]> {
    return this.auditRepo.listRecentForUser(context.userId, 50);
  }

  private async notifySessionRevoked(sessionId?: string, deviceId?: string): Promise<void> {
    try {
      if (!this.env.CHAT_COORDINATOR) return;
      const id = this.env.CHAT_COORDINATOR.idFromName("primary_pair");
      const stub = this.env.CHAT_COORDINATOR.get(id);
      await stub.fetch("https://do/internal/revoke", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sessionId, deviceId }),
      });
    } catch (err) {
      SafeLogger.error("do_revoke_notify_failed", err);
    }
  }
}
