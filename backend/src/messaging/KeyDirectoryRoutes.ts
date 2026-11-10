import { Env } from "../shared/types";
import { errorResponse, jsonResponse } from "../shared/cors";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { UploadPreKeysSchema } from "../shared/validation";
import { UserRepository } from "../database/UserRepository";
import { KeyDirectoryRepository } from "./KeyDirectoryRepository";

export async function handleKeyDirectoryRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";

  if (request.method === "POST" && url.pathname === "/api/v1/keys/bundle") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = UploadPreKeysSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid prekey bundle", 400, origin);
    }

    const repo = new KeyDirectoryRepository(env.DB);
    const counts = await repo.upsertBundle(
      auth.context.userId,
      auth.context.signalDeviceId,
      parsed.data,
      Date.now()
    );

    return jsonResponse({ ok: true, ...counts }, 200, origin);
  }

  if (request.method === "GET" && url.pathname === "/api/v1/keys/partner") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const userRepo = new UserRepository(env.DB);
    const partner = await userRepo.findPartner(auth.context.userId);
    if (!partner) {
      return errorResponse("PARTNER_NOT_PROVISIONED", "Partner account is not configured.", 404, origin);
    }

    const keyRepo = new KeyDirectoryRepository(env.DB);
    const bundle = await keyRepo.popPartnerBundle(partner.id);
    if (!bundle) {
      return errorResponse("PARTNER_KEYS_UNAVAILABLE", "Partner has not uploaded Signal prekeys yet.", 404, origin);
    }

    return jsonResponse(bundle, 200, origin);
  }

  return null;
}
