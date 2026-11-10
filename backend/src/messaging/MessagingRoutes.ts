import { Env } from "../shared/types";
import { errorResponse, jsonResponse } from "../shared/cors";
import { authenticateRequest } from "../authentication/AuthMiddleware";
import { AckReceiptSchema, EditMessageSchema, SendMessageSchema } from "../shared/validation";
import { UserRepository } from "../database/UserRepository";
import { MessageRepository } from "./MessageRepository";
import { defaultWakeDispatcher } from "../notifications/WakeDispatcher";

export async function handleMessagingRoutes(
  request: Request,
  url: URL,
  env: Env
): Promise<Response | null> {
  const origin = env.ALLOWED_ORIGIN || "*";

  if (request.method === "POST" && url.pathname === "/api/v1/messages/send") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = SendMessageSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid message payload", 400, origin);
    }

    const userRepo = new UserRepository(env.DB);
    const partner = await userRepo.findPartner(auth.context.userId);
    if (!partner || partner.id !== parsed.data.recipientUserId) {
      return errorResponse(
        "RECIPIENT_NOT_AUTHORIZED",
        "Messages can only be sent to your authorized conversation partner.",
        403,
        origin
      );
    }

    const msgRepo = new MessageRepository(env.DB);
    const { message, deduplicated } = await msgRepo.saveEncryptedMessage({
      id: parsed.data.id,
      senderUserId: auth.context.userId,
      senderDeviceId: auth.context.signalDeviceId,
      recipientUserId: partner.id,
      messageType: parsed.data.messageType,
      ciphertextB64: parsed.data.ciphertextB64,
      replyToMessageId: parsed.data.replyToMessageId ?? null,
      disappearsAt: parsed.data.disappearsAt ?? null,
      now: Date.now(),
    });

    if (!deduplicated) {
      await defaultWakeDispatcher.dispatchWake(env, partner.id, "encrypted_message", { message });
    }

    return jsonResponse(message, 200, origin);
  }

  if (request.method === "GET" && url.pathname === "/api/v1/messages/sync") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const afterSeqParam = url.searchParams.get("afterSeq") ?? "0";
    const afterSeq = Math.max(0, Number.parseInt(afterSeqParam, 10) || 0);

    const msgRepo = new MessageRepository(env.DB);
    const result = await msgRepo.syncAfterSeq(afterSeq, Date.now());
    return jsonResponse(result, 200, origin);
  }

  if (request.method === "POST" && url.pathname === "/api/v1/messages/ack") {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const raw = await request.json().catch(() => null);
    const parsed = AckReceiptSchema.safeParse(raw);
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid ack payload", 400, origin);
    }

    const msgRepo = new MessageRepository(env.DB);
    const now = Date.now();
    const updatedIds = await msgRepo.ackReceipts(parsed.data.messageIds, auth.context.userId, parsed.data.status, now);

    if (updatedIds.length > 0) {
      const userRepo = new UserRepository(env.DB);
      const partner = await userRepo.findPartner(auth.context.userId);
      if (partner) {
        await broadcastToCoordinator(env, partner.id, {
          type: "receipt_update",
          messageIds: updatedIds,
          status: parsed.data.status,
          updatedAt: now,
        });
      }
    }

    return jsonResponse({ updatedCount: updatedIds.length }, 200, origin);
  }

  const editMatch = url.pathname.match(/^\/api\/v1\/messages\/([^/]+)\/edit$/);
  if (request.method === "POST" && editMatch) {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const messageId = editMatch[1]!;
    const raw = await request.json().catch(() => null);
    const parsed = EditMessageSchema.safeParse(bodyOrNull(raw));
    if (!parsed.success) {
      return errorResponse("INVALID_REQUEST", parsed.error.issues[0]?.message ?? "Invalid edit payload", 400, origin);
    }

    const msgRepo = new MessageRepository(env.DB);
    const updated = await msgRepo.editMessage(messageId, auth.context.userId, parsed.data.ciphertextB64, Date.now());
    if (!updated) {
      return errorResponse("MESSAGE_NOT_EDITABLE", "Message not found or unauthorized.", 404, origin);
    }

    await broadcastToCoordinator(env, updated.recipientUserId, {
      type: "message_edited",
      message: updated,
    });

    return jsonResponse(updated, 200, origin);
  }

  const deleteMatch = url.pathname.match(/^\/api\/v1\/messages\/([^/]+)\/delete$/);
  if (request.method === "POST" && deleteMatch) {
    const auth = await authenticateRequest(request, env);
    if (!auth.ok) return auth.response;

    const messageId = deleteMatch[1]!;
    const msgRepo = new MessageRepository(env.DB);
    const deleted = await msgRepo.deleteMessageForEveryone(messageId, auth.context.userId, Date.now());
    if (!deleted) {
      return errorResponse("MESSAGE_NOT_DELETABLE", "Message not found or unauthorized.", 404, origin);
    }

    await broadcastToCoordinator(env, deleted.recipientUserId, {
      type: "message_deleted",
      messageId: deleted.id,
      serverSeq: deleted.serverSeq,
      updatedAt: deleted.updatedAt,
    });

    return jsonResponse(deleted, 200, origin);
  }

  return null;
}

function bodyOrNull(raw: unknown): unknown {
  return raw ?? null;
}

async function broadcastToCoordinator(env: Env, targetUserId: string, payload: Record<string, unknown>): Promise<void> {
  try {
    if (!env.CHAT_COORDINATOR) return;
    const id = env.CHAT_COORDINATOR.idFromName("primary_pair");
    const stub = env.CHAT_COORDINATOR.get(id);
    await stub.fetch("https://do.internal/internal/broadcast", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ targetUserId, payload }),
    });
  } catch {
    // Best-effort live fan-out; partner reconciles cleanly via /sync
  }
}
