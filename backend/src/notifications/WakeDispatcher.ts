import { Env } from "../shared/types";
import { SafeLogger } from "../shared/logger";

export type WakeEventKind = "sync_hint" | "encrypted_message" | "call_wake";

export interface WakeDispatchResult {
  delivered: boolean;
  transport: "websocket_only" | "future_provider";
  activeConnections: number;
  reason?: string;
}

/**
 * Replaceable background/realtime wake dispatcher abstraction.
 * Firebase / FCM is intentionally excluded from this release; active clients receive
 * wakes over authenticated Durable Object WebSockets, and offline peers recover cleanly
 * via WorkManager background synchronization + `/api/v1/messages/sync`.
 */
export interface NotificationWakeDispatcher {
  dispatchWake(
    env: Env,
    targetUserId: string,
    kind: WakeEventKind,
    payload?: Record<string, unknown>
  ): Promise<WakeDispatchResult>;
}

export class WebSocketOnlyWakeDispatcher implements NotificationWakeDispatcher {
  async dispatchWake(
    env: Env,
    targetUserId: string,
    kind: WakeEventKind,
    payload: Record<string, unknown> = {}
  ): Promise<WakeDispatchResult> {
    try {
      if (!env.CHAT_COORDINATOR) {
        return {
          delivered: false,
          transport: "websocket_only",
          activeConnections: 0,
          reason: "coordinator_unavailable",
        };
      }

      const id = env.CHAT_COORDINATOR.idFromName("primary_pair");
      const stub = env.CHAT_COORDINATOR.get(id);
      const envelope =
        kind === "encrypted_message"
          ? { type: "encrypted_message", ...payload }
          : { type: "wake_hint", kind, ts: Date.now(), ...payload };

      const resp = await stub.fetch("https://do.internal/internal/broadcast", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ targetUserId, payload: envelope }),
      });

      let activeConnections = 0;
      if (resp.ok) {
        const parsed = (await resp.json().catch(() => ({}))) as { activeConnections?: number };
        activeConnections = parsed.activeConnections ?? 0;
      }

      const delivered = activeConnections > 0;
      if (!delivered) {
        SafeLogger.info("wake_deferred_peer_offline", {
          targetUserId,
          kind,
          transport: "websocket_only",
        });
      }

      return {
        delivered,
        transport: "websocket_only",
        activeConnections,
        reason: delivered ? undefined : "peer_offline_deferred_to_workmanager_sync",
      };
    } catch (err) {
      SafeLogger.warn("wake_dispatch_failed", { targetUserId, kind, err: String(err) });
      return {
        delivered: false,
        transport: "websocket_only",
        activeConnections: 0,
        reason: "dispatch_error",
      };
    }
  }
}

export const defaultWakeDispatcher: NotificationWakeDispatcher = new WebSocketOnlyWakeDispatcher();
