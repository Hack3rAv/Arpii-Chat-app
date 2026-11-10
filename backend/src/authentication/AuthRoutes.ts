import { Env } from "../shared/types";
import { errorResponse, jsonResponse } from "../shared/cors";
import {
  ChangePasswordRequestSchema,
  LoginRequestSchema,
  RecoverAccountRequestSchema,
  SaltRequestSchema,
  UpdateProfileSchema,
} from "../shared/validation";
import { AuthService, toPublicProfile } from "./AuthService";
import { authenticateRequest } from "./AuthMiddleware";
import { UserRepository } from "../database/UserRepository";

export async function handleAuthAndProfileRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";
  const clientIp = request.headers.get("CF-Connecting-IP") || request.headers.get("X-Forwarded-For") || "127.0.0.1";
  const authService = new AuthService(env);

  if (request.method === "POST" && url.pathname === "/api/v1/auth/salt") {
    const body = await parseJson(request);
    const parsed = SaltRequestSchema.safeParse(body);
    if (!parsed.success) {
      return errorResponse("VALIDATION_ERROR", "Valid username is required.", 400, origin);
    }
    const result = await authService.getLoginSalt(parsed.data.username, clientIp);
    return result.ok
      ? jsonResponse(result.data, 200, origin)
      : errorResponse(
          result.error.code,
          result.error.message,
          result.error.status,
          origin,
          result.error.retryAfterSeconds ? { "Retry-After": String(result.error.retryAfterSeconds) } : undefined
        );
  }

  if (request.method === "POST" && url.pathname === "/api/v1/auth/login") {
    const body = await parseJson(request);
    const parsed = LoginRequestSchema.safeParse(body);
    if (!parsed.success) {
      return errorResponse("VALIDATION_ERROR", "Invalid login payload format.", 400, origin);
    }
    const result = await authService.login(parsed.data, clientIp);
    if (result.ok) {
      return jsonResponse(result.data, 200, origin);
    }
    return jsonResponse(
      {
        error: {
          code: result.error.code,
          message: result.error.message,
          ...(result.error.pendingDeviceId ? { pendingDeviceId: result.error.pendingDeviceId } : {}),
          ...(result.error.retryAfterSeconds ? { retryAfterSeconds: result.error.retryAfterSeconds } : {}),
        },
      },
      result.error.status,
      origin,
      result.error.retryAfterSeconds ? { "Retry-After": String(result.error.retryAfterSeconds) } : undefined
    );
  }

  if (request.method === "POST" && url.pathname === "/api/v1/auth/recover") {
    const body = await parseJson(request);
    const parsed = RecoverAccountRequestSchema.safeParse(body);
    if (!parsed.success) {
      return errorResponse("VALIDATION_ERROR", "Invalid recovery payload format.", 400, origin);
    }
    const result = await authService.recoverAccount(parsed.data, clientIp);
    return result.ok
      ? jsonResponse(result.data, 200, origin)
      : errorResponse(
          result.error.code,
          result.error.message,
          result.error.status,
          origin,
          result.error.retryAfterSeconds ? { "Retry-After": String(result.error.retryAfterSeconds) } : undefined
        );
  }

  // Protected Auth, Device Approval, Profile, & Security Audit Routes below
  const authCheckNeeded =
    url.pathname === "/api/v1/auth/logout" ||
    url.pathname === "/api/v1/auth/change-password" ||
    url.pathname === "/api/v1/auth/sessions" ||
    url.pathname.startsWith("/api/v1/auth/sessions/") ||
    url.pathname.startsWith("/api/v1/auth/devices/") ||
    url.pathname === "/api/v1/profile" ||
    url.pathname === "/api/v1/security/audit";

  if (!authCheckNeeded) {
    return null;
  }

  const auth = await authenticateRequest(request, env);
  if (!auth.ok) return auth.response;
  const ctx = auth.context;

  if (request.method === "POST" && url.pathname === "/api/v1/auth/logout") {
    await authService.logout(ctx, clientIp);
    return jsonResponse({ status: "LOGGED_OUT" }, 200, origin);
  }

  if (request.method === "POST" && url.pathname === "/api/v1/auth/change-password") {
    const body = await parseJson(request);
    const parsed = ChangePasswordRequestSchema.safeParse(body);
    if (!parsed.success) {
      return errorResponse("VALIDATION_ERROR", "Invalid password rotation payload.", 400, origin);
    }
    const result = await authService.changePassword(ctx, parsed.data, clientIp);
    return result.ok
      ? jsonResponse(result.data, 200, origin)
      : errorResponse(result.error.code, result.error.message, result.error.status, origin);
  }

  if (request.method === "GET" && url.pathname === "/api/v1/auth/sessions") {
    const { sessions, pendingDevices } = await authService.listSessionsAndPendingDevices(ctx);
    return jsonResponse(
      { currentSessionId: ctx.sessionId, currentDeviceId: ctx.deviceId, sessions, pendingDevices },
      200,
      origin
    );
  }

  if (request.method === "POST" && url.pathname.startsWith("/api/v1/auth/sessions/") && url.pathname.endsWith("/revoke")) {
    const segments = url.pathname.split("/");
    const sessionId = segments[5];
    if (!sessionId) {
      return errorResponse("VALIDATION_ERROR", "Missing session ID.", 400, origin);
    }
    const revoked = await authService.revokeSession(ctx, sessionId, clientIp);
    if (!revoked) {
      return errorResponse("NOT_FOUND", "Active session not found.", 404, origin);
    }
    return jsonResponse({ revoked: true, sessionId }, 200, origin);
  }

  if (request.method === "POST" && url.pathname.startsWith("/api/v1/auth/devices/") && url.pathname.endsWith("/approve")) {
    const segments = url.pathname.split("/");
    const deviceId = segments[5];
    if (!deviceId) {
      return errorResponse("VALIDATION_ERROR", "Missing device ID.", 400, origin);
    }
    const approved = await authService.approveDevice(ctx, deviceId, clientIp);
    if (!approved) {
      return errorResponse("NOT_FOUND", "Pending device not found.", 404, origin);
    }
    return jsonResponse({ approved: true, deviceId }, 200, origin);
  }

  if (request.method === "POST" && url.pathname.startsWith("/api/v1/auth/devices/") && url.pathname.endsWith("/reject")) {
    const segments = url.pathname.split("/");
    const deviceId = segments[5];
    if (!deviceId) {
      return errorResponse("VALIDATION_ERROR", "Missing device ID.", 400, origin);
    }
    const rejected = await authService.rejectDevice(ctx, deviceId, clientIp);
    if (!rejected) {
      return errorResponse("NOT_FOUND", "Pending device not found.", 404, origin);
    }
    return jsonResponse({ rejected: true, deviceId }, 200, origin);
  }

  if (request.method === "POST" && url.pathname.startsWith("/api/v1/auth/devices/") && url.pathname.endsWith("/revoke")) {
    const segments = url.pathname.split("/");
    const deviceId = segments[5];
    if (!deviceId) {
      return errorResponse("VALIDATION_ERROR", "Missing device ID.", 400, origin);
    }
    const revoked = await authService.revokeDevice(ctx, deviceId, clientIp);
    if (!revoked) {
      return errorResponse("NOT_FOUND", "Active device not found.", 404, origin);
    }
    return jsonResponse({ revoked: true, deviceId }, 200, origin);
  }

  if (request.method === "GET" && url.pathname === "/api/v1/security/audit") {
    const events = await authService.listAuditEvents(ctx);
    return jsonResponse({ events }, 200, origin);
  }

  if (request.method === "GET" && url.pathname === "/api/v1/profile") {
    const userRepo = new UserRepository(env.DB);
    const self = await userRepo.findById(ctx.userId);
    const partner = await userRepo.findPartner(ctx.userId);
    if (!self) {
      return errorResponse("NOT_FOUND", "Profile not found.", 404, origin);
    }
    return jsonResponse(
      {
        selfUser: toPublicProfile(self, true),
        partnerUser: partner ? toPublicProfile(partner, false) : null,
      },
      200,
      origin
    );
  }

  if (request.method === "PATCH" && url.pathname === "/api/v1/profile") {
    const body = await parseJson(request);
    const parsed = UpdateProfileSchema.safeParse(body);
    if (!parsed.success) {
      return errorResponse("VALIDATION_ERROR", "Invalid profile update.", 400, origin);
    }
    const userRepo = new UserRepository(env.DB);
    await userRepo.updateProfile(
      ctx.userId,
      {
        displayName: parsed.data.displayName,
        about: parsed.data.about,
        avatarR2Key: parsed.data.avatarR2Key,
        avatarKeyBase64: parsed.data.avatarKeyBase64,
        privacyShowLastSeen: parsed.data.privacyShowLastSeen === undefined ? undefined : parsed.data.privacyShowLastSeen ? 1 : 0,
        privacyShowOnline: parsed.data.privacyShowOnline === undefined ? undefined : parsed.data.privacyShowOnline ? 1 : 0,
      },
      Date.now()
    );
    const updated = await userRepo.findById(ctx.userId);
    const partner = await userRepo.findPartner(ctx.userId);
    return jsonResponse(
      {
        selfUser: updated ? toPublicProfile(updated, true) : null,
        partnerUser: partner ? toPublicProfile(partner, false) : null,
      },
      200,
      origin
    );
  }

  return null;
}

async function parseJson(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    return null;
  }
}
