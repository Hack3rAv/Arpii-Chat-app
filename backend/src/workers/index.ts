import { Env } from "../shared/types";
import { errorResponse, jsonResponse, securityHeaders } from "../shared/cors";
import { handleAuthAndProfileRoutes } from "../authentication/AuthRoutes";
import { handleKeyDirectoryRoutes } from "../messaging/KeyDirectoryRoutes";
import { handleMessagingRoutes } from "../messaging/MessagingRoutes";
import { handleMediaStorageRoutes } from "../storage/MediaStorageRoutes";
import { handleBackupManifestRoutes } from "../storage/BackupManifestRoutes";
import { handleCallSignalingRoutes } from "../signaling/CallSignalingRoutes";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { SafeLogger } from "../shared/logger";
import { ChatCoordinatorDO } from "../durable-objects/ChatCoordinatorDO";

export { ChatCoordinatorDO };

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const origin = env.ALLOWED_ORIGIN || "*";

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: securityHeaders(origin) });
    }

    const url = new URL(request.url);

    try {
      if (request.method === "GET" && url.pathname === "/api/v1/health") {
        let d1Ok = false;
        try {
          const probe = await env.DB.prepare("SELECT 1 as ok").first<{ ok: number }>();
          d1Ok = probe?.ok === 1;
        } catch {
          d1Ok = false;
        }
        const r2Configured = Boolean(env.MEDIA_BUCKET);
        const turnConfigured = Boolean(env.CF_TURN_KEY_ID?.trim() && env.CF_TURN_API_TOKEN?.trim());

        return jsonResponse(
          {
            status: d1Ok && r2Configured ? "HEALTHY" : "DEGRADED",
            service: "system-utility-backend",
            environment: env.ENVIRONMENT || "unknown",
            checks: {
              d1: d1Ok ? "ok" : "error",
              r2: r2Configured ? "bound" : "missing",
              turnConfigured,
              firebaseExcluded: true,
              driveBackupDeferred: true,
            },
            timestamp: Date.now(),
          },
          d1Ok && r2Configured ? 200 : 503,
          origin
        );
      }

      const authResponse = await handleAuthAndProfileRoutes(request, url, env);
      if (authResponse) {
        return authResponse;
      }

      const keyResponse = await handleKeyDirectoryRoutes(request, url, env);
      if (keyResponse) {
        return keyResponse;
      }

      const msgResponse = await handleMessagingRoutes(request, url, env);
      if (msgResponse) {
        return msgResponse;
      }

      const mediaResponse = await handleMediaStorageRoutes(request, url, env);
      if (mediaResponse) {
        return mediaResponse;
      }

      const backupResponse = await handleBackupManifestRoutes(request, url, env);
      if (backupResponse) {
        return backupResponse;
      }

      const callResponse = await handleCallSignalingRoutes(request, url, env);
      if (callResponse) {
        return callResponse;
      }

      if (url.pathname === "/api/v1/ws") {
        const auth = await authenticateRequest(request, env);
        if (!auth.ok) return auth.response;

        const id = env.CHAT_COORDINATOR.idFromName("primary_pair");
        const stub = env.CHAT_COORDINATOR.get(id);

        const doHeaders = new Headers(request.headers);
        doHeaders.set("X-Auth-Context", JSON.stringify(auth.context));
        const doUrl = new URL(request.url);
        doUrl.pathname = "/ws";

        return stub.fetch(new Request(doUrl.toString(), { headers: doHeaders }));
      }

      return errorResponse("NOT_FOUND", "Endpoint not found.", 404, origin);
    } catch (err) {
      SafeLogger.error("worker_unhandled_exception", err, { path: url.pathname, method: request.method });
      return errorResponse("INTERNAL_ERROR", "An unexpected server error occurred.", 500, origin);
    }
  },
};
