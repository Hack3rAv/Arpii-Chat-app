import { Env } from "../shared/types";
import { errorResponse, jsonResponse } from "../shared/cors";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { CallLogSchema } from "../shared/validation";
import { UserRepository } from "../database/UserRepository";
import { AuditRepository } from "../database/AuditRepository";
import { SafeLogger } from "../shared/logger";
import { defaultWakeDispatcher } from "../notifications/WakeDispatcher";

interface CloudflareIceServerRaw {
  urls?: string | string[];
  username?: string;
  credential?: string;
}

export interface NormalizedIceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

function getPepper(env: Env): string {
  return env.AUTH_PEPPER || "ephemeral_fallback_set_AUTH_PEPPER_secret";
}

export async function handleCallSignalingRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";
  const clientIp = request.headers.get("CF-Connecting-IP") || request.headers.get("X-Forwarded-For") || "127.0.0.1";
  const auditRepo = new AuditRepository(env.DB, getPepper(env));

  if (request.method === "GET" && url.pathname === "/api/v1/turn-credentials") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const requestedTtl = Number.parseInt(url.searchParams.get("ttl") ?? "3600", 10);
    const ttlSeconds = Number.isFinite(requestedTtl) ? Math.min(Math.max(requestedTtl, 60), 86400) : 3600;

    const keyId = env.CF_TURN_KEY_ID?.trim();
    const apiToken = env.CF_TURN_API_TOKEN?.trim();
    const now = Date.now();

    if (!keyId || !apiToken) {
      SafeLogger.warn("turn_credentials_not_configured", { userId: auth.context.userId });
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "TURN_CREDENTIALS_FAILED",
        severity: "WARN",
        clientIp,
        metadata: { reason: "missing_turn_secrets" },
        now,
      });
      return errorResponse(
        "TURN_NOT_CONFIGURED",
        "Cloudflare Realtime TURN secrets (CF_TURN_KEY_ID, CF_TURN_API_TOKEN) are not configured on this Worker.",
        503,
        origin
      );
    }

    const endpoint = `https://rtc.live.cloudflare.com/v1/turn/keys/${encodeURIComponent(keyId)}/credentials/generate-ice-servers`;

    let upstreamResp: Response;
    try {
      upstreamResp = await fetch(endpoint, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ ttl: ttlSeconds }),
      });
    } catch (err) {
      SafeLogger.error("cloudflare_turn_network_error", err, { userId: auth.context.userId });
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "TURN_CREDENTIALS_FAILED",
        severity: "WARN",
        clientIp,
        metadata: { reason: "network_error" },
        now,
      });
      return errorResponse("TURN_UPSTREAM_ERROR", "Unable to reach Cloudflare Realtime TURN API.", 502, origin);
    }

    if (!upstreamResp.ok) {
      SafeLogger.warn("cloudflare_turn_upstream_rejected", {
        status: upstreamResp.status,
        userId: auth.context.userId,
      });
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "TURN_CREDENTIALS_FAILED",
        severity: "WARN",
        clientIp,
        metadata: { httpStatus: upstreamResp.status },
        now,
      });
      return errorResponse(
        "TURN_UPSTREAM_ERROR",
        `Cloudflare Realtime TURN returned HTTP ${upstreamResp.status}. Verify CF_TURN_KEY_ID (TURN Key ID, not App ID) and CF_TURN_API_TOKEN.`,
        502,
        origin
      );
    }

    const parsed = (await upstreamResp.json().catch(() => null)) as { iceServers?: CloudflareIceServerRaw[] } | null;
    const rawServers = parsed?.iceServers ?? [];
    const iceServers: NormalizedIceServer[] = rawServers
      .map((entry) => {
        const urls = Array.isArray(entry.urls)
          ? entry.urls.filter((u) => typeof u === "string" && u.trim().length > 0)
          : typeof entry.urls === "string" && entry.urls.trim().length > 0
            ? [entry.urls.trim()]
            : [];
        if (urls.length === 0) return null;
        return {
          urls,
          ...(entry.username ? { username: entry.username } : {}),
          ...(entry.credential ? { credential: entry.credential } : {}),
        };
      })
      .filter((s): s is NormalizedIceServer => s !== null);

    if (iceServers.length === 0) {
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "TURN_CREDENTIALS_FAILED",
        severity: "WARN",
        clientIp,
        metadata: { reason: "empty_ice_servers" },
        now,
      });
      return errorResponse("TURN_UPSTREAM_ERROR", "Cloudflare Realtime TURN returned no ICE servers.", 502, origin);
    }

    const expiresAt = now + ttlSeconds * 1000;
    await auditRepo.recordEvent({
      actorUserId: auth.context.userId,
      targetDeviceId: auth.context.deviceId,
      eventType: "TURN_CREDENTIALS_ISSUED",
      severity: "INFO",
      clientIp,
      metadata: { ttlSeconds, serverCount: iceServers.length },
      now,
    });

    return jsonResponse(
      {
        provider: "cloudflare_realtime_turn",
        ttlSeconds,
        expiresAt,
        iceServers,
      },
      200,
      origin
    );
  }

  if (request.method === "POST" && url.pathname === "/api/v1/calls/log") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = CallLogSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid call log payload", 400, origin);
    }

    const userRepo = new UserRepository(env.DB);
    const partner = await userRepo.findPartner(auth.context.userId);
    if (!partner || (parsed.data.calleeUserId !== partner.id && parsed.data.calleeUserId !== auth.context.userId)) {
      return errorResponse("FORBIDDEN", "Calls are strictly restricted to your paired partner.", 403, origin);
    }

    const d = parsed.data;
    await env.DB.prepare(
      `INSERT INTO call_logs (id, caller_user_id, callee_user_id, call_type, status, started_at, answered_at, ended_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         status = excluded.status,
         answered_at = COALESCE(excluded.answered_at, call_logs.answered_at),
         ended_at = COALESCE(excluded.ended_at, call_logs.ended_at)`
    )
      .bind(
        d.id,
        auth.context.userId,
        d.calleeUserId,
        d.callType,
        d.status,
        d.startedAt,
        d.answeredAt ?? null,
        d.endedAt ?? null
      )
      .run();

    // Dispatch wake signal over authenticated WebSocket when initiating/ringing (no FCM)
    if (d.status === "INITIATED" || d.status === "RINGING") {
      await defaultWakeDispatcher.dispatchWake(env, partner.id, "call_wake", {
        callId: d.id,
        callType: d.callType,
      });
    }

    return jsonResponse({ ok: true }, 200, origin);
  }

  return null;
}
