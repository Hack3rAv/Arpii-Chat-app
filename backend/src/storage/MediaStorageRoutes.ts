import { Env } from "../shared/types";
import { errorResponse, jsonResponse, securityHeaders } from "../shared/cors";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { MediaDownloadAuthSchema, MediaUploadInitSchema } from "../shared/validation";
import { constantTimeEqualHex, generateUuid, hmacSha256Hex, sha256Hex } from "../shared/crypto";
import { UserRepository } from "../database/UserRepository";
import { AuditRepository } from "../database/AuditRepository";

const UPLOAD_GRANT_TTL_MS = 15 * 60 * 1000; // 15 minutes
const DOWNLOAD_GRANT_TTL_MS = 120 * 1000; // 120 seconds

interface MediaObjectRow {
  id: string;
  uploader_user_id: string;
  r2_object_key: string;
  encrypted_size_bytes: number;
  sha256_ciphertext: string;
  mime_category: string;
  upload_completed: number;
  upload_expires_at: number | null;
  expires_at: number | null;
}

function getPepper(env: Env): string {
  return env.AUTH_PEPPER || "ephemeral_fallback_set_AUTH_PEPPER_secret";
}

async function signDownloadGrant(
  pepper: string,
  userId: string,
  r2ObjectKey: string,
  expiresAt: number
): Promise<string> {
  const sig = await hmacSha256Hex(pepper, `dl:${userId}:${r2ObjectKey}:${expiresAt}`);
  return `${expiresAt}.${sig}`;
}

async function verifyDownloadGrant(
  pepper: string,
  userId: string,
  r2ObjectKey: string,
  grantToken: string | null,
  now: number
): Promise<{ ok: true } | { ok: false; code: string; message: string }> {
  if (!grantToken) {
    return {
      ok: false,
      code: "DOWNLOAD_GRANT_REQUIRED",
      message: "Short-lived download authorization grant is required.",
    };
  }
  const parts = grantToken.split(".");
  if (parts.length !== 2) {
    return { ok: false, code: "DOWNLOAD_GRANT_INVALID", message: "Malformed download authorization grant." };
  }
  const expMs = Number.parseInt(parts[0]!, 10);
  const sigHex = parts[1]!;
  if (!Number.isFinite(expMs) || !/^[a-f0-9]{64}$/.test(sigHex)) {
    return { ok: false, code: "DOWNLOAD_GRANT_INVALID", message: "Invalid download authorization grant format." };
  }

  const expectedSig = await hmacSha256Hex(pepper, `dl:${userId}:${r2ObjectKey}:${expMs}`);
  if (!constantTimeEqualHex(sigHex, expectedSig)) {
    return { ok: false, code: "DOWNLOAD_GRANT_INVALID", message: "Download authorization signature rejected." };
  }
  if (now > expMs) {
    return { ok: false, code: "DOWNLOAD_GRANT_EXPIRED", message: "Short-lived download grant has expired." };
  }
  return { ok: true };
}

export async function handleMediaStorageRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";
  const maxBytes = Number.parseInt(env.MAX_UPLOAD_BYTES || "52428800", 10) || 52_428_800;
  const clientIp = request.headers.get("CF-Connecting-IP") || request.headers.get("X-Forwarded-For") || "127.0.0.1";
  const pepper = getPepper(env);
  const auditRepo = new AuditRepository(env.DB, pepper);

  if (request.method === "POST" && url.pathname === "/api/v1/media/init-upload") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = MediaUploadInitSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid media init payload", 400, origin);
    }

    if (parsed.data.encryptedSizeBytes > maxBytes) {
      return errorResponse(
        "PAYLOAD_TOO_LARGE",
        `Encrypted media exceeds maximum allowed limit (${maxBytes} bytes).`,
        413,
        origin
      );
    }

    const mediaId = generateUuid();
    const r2ObjectKey = `enc/${auth.context.userId}/${mediaId}.bin`;
    const retentionDays = Number.parseInt(env.RETENTION_DAYS || "90", 10) || 90;
    const now = Date.now();
    const uploadExpiresAt = now + UPLOAD_GRANT_TTL_MS;
    const expiresAt = now + retentionDays * 86_400_000;

    await env.DB.prepare(
      `INSERT INTO encrypted_media_objects (
        id, uploader_user_id, r2_object_key, encrypted_size_bytes,
        sha256_ciphertext, mime_category, upload_completed, upload_expires_at, expires_at, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`
    )
      .bind(
        mediaId,
        auth.context.userId,
        r2ObjectKey,
        parsed.data.encryptedSizeBytes,
        parsed.data.sha256Ciphertext,
        parsed.data.mimeCategory,
        uploadExpiresAt,
        expiresAt,
        now
      )
      .run();

    await auditRepo.recordEvent({
      actorUserId: auth.context.userId,
      targetDeviceId: auth.context.deviceId,
      eventType: "MEDIA_UPLOAD_AUTHORIZED",
      severity: "INFO",
      clientIp,
      metadata: { mediaId, mimeCategory: parsed.data.mimeCategory, uploadExpiresAt },
      now,
    });

    return jsonResponse({ mediaId, r2ObjectKey, uploadExpiresAt }, 200, origin);
  }

  const putMatch = url.pathname.match(/^\/api\/v1\/media\/blob\/([^/]+)$/);
  if (request.method === "PUT" && putMatch) {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const mediaId = putMatch[1]!;
    const now = Date.now();
    const row = await env.DB.prepare(
      "SELECT * FROM encrypted_media_objects WHERE id = ? AND uploader_user_id = ? LIMIT 1"
    )
      .bind(mediaId, auth.context.userId)
      .first<MediaObjectRow>();

    if (!row) {
      return errorResponse("MEDIA_NOT_FOUND", "Media upload session not found.", 404, origin);
    }

    if (row.upload_completed === 1) {
      return errorResponse("ALREADY_UPLOADED", "Upload session has already been finalized.", 409, origin);
    }

    if (row.upload_expires_at !== null && now > row.upload_expires_at) {
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "MEDIA_UPLOAD_EXPIRED",
        severity: "WARN",
        clientIp,
        metadata: { mediaId },
        now,
      });
      return errorResponse("UPLOAD_GRANT_EXPIRED", "Short-lived upload authorization window has expired.", 403, origin);
    }

    const buf = await request.arrayBuffer();
    if (buf.byteLength === 0) {
      return errorResponse("EMPTY_PAYLOAD", "Encrypted upload body cannot be empty.", 400, origin);
    }
    if (buf.byteLength > maxBytes) {
      return errorResponse("PAYLOAD_TOO_LARGE", "Encrypted blob exceeds size limit.", 413, origin);
    }

    const actualSha = await sha256Hex(new Uint8Array(buf));
    if (actualSha !== row.sha256_ciphertext) {
      return errorResponse("INTEGRITY_MISMATCH", "Ciphertext SHA-256 verification failed.", 400, origin);
    }

    await env.MEDIA_BUCKET.put(row.r2_object_key, buf, {
      httpMetadata: {
        contentType: "application/octet-stream",
      },
      customMetadata: {
        uploaderUserId: row.uploader_user_id,
        sha256: row.sha256_ciphertext,
      },
    });

    await env.DB.prepare(
      "UPDATE encrypted_media_objects SET upload_completed = 1, encrypted_size_bytes = ? WHERE id = ?"
    )
      .bind(buf.byteLength, row.id)
      .run();

    await auditRepo.recordEvent({
      actorUserId: auth.context.userId,
      targetDeviceId: auth.context.deviceId,
      eventType: "MEDIA_UPLOAD_COMPLETED",
      severity: "INFO",
      clientIp,
      metadata: { mediaId: row.id, encryptedSizeBytes: buf.byteLength },
      now,
    });

    return jsonResponse({ ok: true, r2ObjectKey: row.r2_object_key, encryptedSizeBytes: buf.byteLength }, 200, origin);
  }

  if (request.method === "POST" && url.pathname === "/api/v1/media/authorize-download") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = MediaDownloadAuthSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", "Valid r2ObjectKey is required.", 400, origin);
    }

    const objectKey = parsed.data.r2ObjectKey;
    if (!objectKey.startsWith("enc/") || objectKey.includes("..")) {
      return errorResponse("INVALID_OBJECT_KEY", "Invalid media key.", 400, origin);
    }

    const now = Date.now();
    const meta = await env.DB.prepare(
      `SELECT id, uploader_user_id, upload_completed, expires_at
       FROM encrypted_media_objects
       WHERE r2_object_key = ?
       LIMIT 1`
    )
      .bind(objectKey)
      .first<{ id: string; uploader_user_id: string; upload_completed: number; expires_at: number | null }>();

    if (!meta || meta.upload_completed !== 1) {
      return errorResponse("MEDIA_NOT_FOUND", "Encrypted object does not exist.", 404, origin);
    }

    if (meta.expires_at !== null && now > meta.expires_at) {
      return errorResponse("MEDIA_EXPIRED", "Encrypted media object retention window expired.", 410, origin);
    }

    const userRepo = new UserRepository(env.DB);
    const partner = await userRepo.findPartner(auth.context.userId);
    const authorized =
      meta.uploader_user_id === auth.context.userId || (partner !== null && meta.uploader_user_id === partner.id);

    if (!authorized) {
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "MEDIA_DOWNLOAD_DENIED",
        severity: "SECURITY",
        clientIp,
        metadata: { objectKey, reason: "acl_mismatch" },
        now,
      });
      return errorResponse("FORBIDDEN", "Not authorized to download this media object.", 403, origin);
    }

    const expiresAt = now + DOWNLOAD_GRANT_TTL_MS;
    const grant = await signDownloadGrant(pepper, auth.context.userId, objectKey, expiresAt);

    await auditRepo.recordEvent({
      actorUserId: auth.context.userId,
      targetDeviceId: auth.context.deviceId,
      eventType: "MEDIA_DOWNLOAD_AUTHORIZED",
      severity: "INFO",
      clientIp,
      metadata: { mediaId: meta.id, expiresAt },
      now,
    });

    return jsonResponse(
      {
        r2ObjectKey: objectKey,
        grant,
        expiresAt,
        downloadPath: `/api/v1/media/blob/${objectKey}?grant=${encodeURIComponent(grant)}`,
      },
      200,
      origin
    );
  }

  if (request.method === "GET" && url.pathname.startsWith("/api/v1/media/blob/")) {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const rawTail = url.pathname.slice("/api/v1/media/blob/".length);
    const objectKey = decodeURIComponent(rawTail);
    if (!objectKey.startsWith("enc/") || objectKey.includes("..")) {
      return errorResponse("INVALID_OBJECT_KEY", "Invalid media key.", 400, origin);
    }

    const now = Date.now();
    const meta = await env.DB.prepare(
      `SELECT id, uploader_user_id, upload_completed, expires_at
       FROM encrypted_media_objects
       WHERE r2_object_key = ?
       LIMIT 1`
    )
      .bind(objectKey)
      .first<{ id: string; uploader_user_id: string; upload_completed: number; expires_at: number | null }>();

    if (!meta || meta.upload_completed !== 1) {
      return errorResponse("MEDIA_NOT_FOUND", "Encrypted object does not exist.", 404, origin);
    }

    if (meta.expires_at !== null && now > meta.expires_at) {
      return errorResponse("MEDIA_EXPIRED", "Encrypted object retention expired.", 410, origin);
    }

    const userRepo = new UserRepository(env.DB);
    const partner = await userRepo.findPartner(auth.context.userId);
    const authorized =
      meta.uploader_user_id === auth.context.userId || (partner !== null && meta.uploader_user_id === partner.id);

    if (!authorized) {
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "MEDIA_DOWNLOAD_DENIED",
        severity: "SECURITY",
        clientIp,
        metadata: { objectKey, reason: "acl_mismatch" },
        now,
      });
      return errorResponse("FORBIDDEN", "Not authorized to download this media object.", 403, origin);
    }

    const grantCheck = await verifyDownloadGrant(
      pepper,
      auth.context.userId,
      objectKey,
      url.searchParams.get("grant"),
      now
    );
    if (!grantCheck.ok) {
      await auditRepo.recordEvent({
        actorUserId: auth.context.userId,
        targetDeviceId: auth.context.deviceId,
        eventType: "MEDIA_DOWNLOAD_DENIED",
        severity: "SECURITY",
        clientIp,
        metadata: { objectKey, reason: grantCheck.code },
        now,
      });
      return errorResponse(grantCheck.code, grantCheck.message, 403, origin);
    }

    const obj = await env.MEDIA_BUCKET.get(objectKey);
    if (!obj) {
      return errorResponse("MEDIA_MISSING", "Encrypted object missing in bucket.", 404, origin);
    }

    const headers = securityHeaders(origin);
    headers["Content-Type"] = "application/octet-stream";
    headers["Cache-Control"] = "no-store, max-age=0";
    return new Response(obj.body, { status: 200, headers });
  }

  return null;
}
