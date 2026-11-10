import { AuditEventRow, AuditSeverity } from "../shared/types";
import { generateUuid, sha256Hex } from "../shared/crypto";
import { SafeLogger } from "../shared/logger";

export interface AuditEventView {
  id: string;
  actorUserId: string | null;
  targetDeviceId: string | null;
  eventType: string;
  severity: AuditSeverity;
  metadata: Record<string, unknown>;
  createdAt: number;
}

export class AuditRepository {
  constructor(
    private readonly db: D1Database,
    private readonly pepper: string
  ) {}

  async hashIp(clientIp: string | undefined): Promise<string | null> {
    if (!clientIp) return null;
    const digest = await sha256Hex(`${this.pepper}:${clientIp}`);
    return digest.slice(0, 16);
  }

  async recordEvent(input: {
    actorUserId?: string | null;
    targetDeviceId?: string | null;
    eventType: string;
    severity?: AuditSeverity;
    clientIp?: string;
    metadata?: Record<string, unknown>;
    now?: number;
  }): Promise<void> {
    try {
      const id = generateUuid();
      const now = input.now ?? Date.now();
      const ipHash = await this.hashIp(input.clientIp);
      const metadataJson = JSON.stringify(input.metadata ?? {});

      await this.db
        .prepare(
          `INSERT INTO audit_events (
             id, actor_user_id, target_device_id, event_type, severity, ip_hash, metadata_json, created_at
           ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind(
          id,
          input.actorUserId ?? null,
          input.targetDeviceId ?? null,
          input.eventType,
          input.severity ?? "INFO",
          ipHash,
          metadataJson,
          now
        )
        .run();
    } catch (err) {
      SafeLogger.warn("audit_event_write_failed", { eventType: input.eventType, err: String(err) });
    }
  }

  async listRecentForUser(userId: string, limit = 50): Promise<AuditEventView[]> {
    const safeLimit = Math.min(Math.max(1, limit), 100);
    const res = await this.db
      .prepare(
        `SELECT id, actor_user_id, target_device_id, event_type, severity, ip_hash, metadata_json, created_at
         FROM audit_events
         WHERE actor_user_id = ?
         ORDER BY created_at DESC
         LIMIT ?`
      )
      .bind(userId, safeLimit)
      .all<AuditEventRow>();

    return (res.results ?? []).map((r) => {
      let metadata: Record<string, unknown> = {};
      try {
        metadata = JSON.parse(r.metadata_json) as Record<string, unknown>;
      } catch {
        metadata = {};
      }
      return {
        id: r.id,
        actorUserId: r.actor_user_id,
        targetDeviceId: r.target_device_id,
        eventType: r.event_type,
        severity: r.severity,
        metadata,
        createdAt: r.created_at,
      };
    });
  }
}
