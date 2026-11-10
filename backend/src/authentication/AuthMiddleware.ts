import { AuthContext, Env } from "../shared/types";
import { SessionRepository } from "../database/SessionRepository";
import { sha256Hex } from "../shared/crypto";
import { errorResponse } from "../shared/cors";

export async function authenticateRequest(
  request: Request,
  env: Env,
  now = Date.now()
): Promise<{ ok: true; context: AuthContext } | { ok: false; response: Response }> {
  const authHeader = request.headers.get("Authorization") ?? "";
  if (!authHeader.startsWith("Bearer ")) {
    return {
      ok: false,
      response: errorResponse("UNAUTHORIZED", "Missing or invalid authorization header.", 401, env.ALLOWED_ORIGIN),
    };
  }

  const rawToken = authHeader.slice("Bearer ".length).trim();
  if (!rawToken || rawToken.length < 32) {
    return {
      ok: false,
      response: errorResponse("UNAUTHORIZED", "Malformed session token.", 401, env.ALLOWED_ORIGIN),
    };
  }

  const deviceId = request.headers.get("X-Device-Id");
  const tokenHash = await sha256Hex(rawToken);
  const sessionRepo = new SessionRepository(env.DB);
  const authContext = await sessionRepo.validateToken(tokenHash, deviceId, now);

  if (!authContext) {
    return {
      ok: false,
      response: errorResponse("SESSION_REVOKED", "Session is invalid, expired, or has been revoked.", 401, env.ALLOWED_ORIGIN),
    };
  }

  return { ok: true, context: authContext };
}
