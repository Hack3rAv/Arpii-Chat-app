import { Env } from "../shared/types";
import { errorResponse, jsonResponse } from "../shared/cors";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { BackupManifestSchema } from "../shared/validation";
import { generateUuid } from "../shared/crypto";

interface BackupManifestRow {
  id: string;
  user_id: string;
  drive_file_id: string;
  version_tag: string;
  size_bytes: number;
  sha256_ciphertext: string;
  created_at: number;
}

export async function handleBackupManifestRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";

  if (url.pathname !== "/api/v1/backups/manifest") {
    return null;
  }

  const auth = await authenticateRequest(request, env);
  if (!auth.ok) return auth.response;

  if (request.method === "POST") {
    const raw = await request.json().catch(() => null);
    const parsed = BackupManifestSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse(
        "INVALID_REQUEST",
        parsed.error.issues[0]?.message ?? "Invalid backup manifest payload.",
        400,
        origin
      );
    }

    const id = generateUuid();
    const now = Date.now();

    await env.DB.prepare(
      `INSERT INTO backup_manifests (
        id, user_id, drive_file_id, version_tag, size_bytes, sha256_ciphertext, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?)`
    )
      .bind(
        id,
        auth.context.userId,
        parsed.data.driveFileId,
        parsed.data.versionTag,
        parsed.data.sizeBytes,
        parsed.data.sha256Ciphertext,
        now
      )
      .run();

    return jsonResponse(
      {
        id,
        driveFileId: parsed.data.driveFileId,
        versionTag: parsed.data.versionTag,
        sizeBytes: parsed.data.sizeBytes,
        sha256Ciphertext: parsed.data.sha256Ciphertext,
        createdAt: now,
      },
      200,
      origin
    );
  }

  if (request.method === "GET") {
    const rows = await env.DB.prepare(
      `SELECT id, user_id, drive_file_id, version_tag, size_bytes, sha256_ciphertext, created_at
       FROM backup_manifests
       WHERE user_id = ?
       ORDER BY created_at DESC
       LIMIT 20`
    )
      .bind(auth.context.userId)
      .all<BackupManifestRow>();

    return jsonResponse(
      {
        manifests: (rows.results ?? []).map((r) => ({
          id: r.id,
          driveFileId: r.drive_file_id,
          versionTag: r.version_tag,
          sizeBytes: r.size_bytes,
          sha256Ciphertext: r.sha256_ciphertext,
          createdAt: r.created_at,
        })),
      },
      200,
      origin
    );
  }

  return errorResponse("METHOD_NOT_ALLOWED", "Unsupported HTTP method.", 405, origin);
}
