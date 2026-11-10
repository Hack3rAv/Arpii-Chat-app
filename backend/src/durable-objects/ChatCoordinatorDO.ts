import { AuthContext, Env } from "../shared/types";
import { SafeLogger } from "../shared/logger";

interface WebSocketAttachment {
  userId: string;
  slotId: number;
  deviceId: string;
  signalDeviceId: number;
  sessionId: string;
}

/**
 * Stateful Cloudflare Durable Object coordinating real-time presence,
 * typing indicators, encrypted message envelope delivery, read receipts,
 * WebRTC call signaling, and immediate session revocation for the 2-user pair.
 */
export class ChatCoordinatorDO implements DurableObject {
  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env
  ) {}

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "POST" && url.pathname === "/internal/revoke") {
      const body = (await request.json()) as { sessionId?: string; deviceId?: string };
      await this.disconnectMatchingSockets(body.sessionId, body.deviceId);
      return new Response(JSON.stringify({ disconnected: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    if (request.method === "POST" && url.pathname === "/internal/broadcast") {
      const body = (await request.json()) as { targetUserId: string; payload: Record<string, unknown> };
      const activeConnections = this.sendToUser(body.targetUserId, JSON.stringify(body.payload));
      return new Response(JSON.stringify({ forwarded: true, activeConnections }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }

    if (url.pathname === "/ws") {
      const upgradeHeader = request.headers.get("Upgrade");
      if (!upgradeHeader || upgradeHeader.toLowerCase() !== "websocket") {
        return new Response("Expected Upgrade: websocket", { status: 426 });
      }

      const authHeader = request.headers.get("X-Auth-Context");
      if (!authHeader) {
        return new Response("Unauthorized", { status: 401 });
      }
      const authCtx = JSON.parse(authHeader) as AuthContext;

      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair) as [WebSocket, WebSocket];

      const attachment: WebSocketAttachment = {
        userId: authCtx.userId,
        slotId: authCtx.slotId,
        deviceId: authCtx.deviceId,
        signalDeviceId: authCtx.signalDeviceId,
        sessionId: authCtx.sessionId,
      };

      this.state.acceptWebSocket(server);
      server.serializeAttachment(attachment);

      SafeLogger.info("do_ws_connected", {
        userId: authCtx.userId,
        deviceId: authCtx.deviceId,
      });

      this.env.DB.prepare("UPDATE users SET last_seen_at = ? WHERE id = ?")
        .bind(Date.now(), authCtx.userId)
        .run()
        .catch(() => {});

      this.broadcastPresence(authCtx.userId, true);

      return new Response(null, { status: 101, webSocket: client });
    }

    return new Response("Not Found", { status: 404 });
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    if (typeof message !== "string") return;
    const sender = ws.deserializeAttachment() as WebSocketAttachment | null;
    if (!sender) {
      ws.close(1008, "Missing session state");
      return;
    }

    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(message) as Record<string, unknown>;
    } catch {
      ws.send(JSON.stringify({ type: "error", code: "INVALID_JSON" }));
      return;
    }

    const type = parsed["type"];
    if (type === "ping") {
      ws.send(JSON.stringify({ type: "pong", ts: Date.now() }));
      return;
    }

    if (type === "typing" || type === "receipt" || type === "signal_envelope" || type === "call_signal") {
      const envelope = JSON.stringify({
        ...parsed,
        fromUserId: sender.userId,
        fromDeviceId: sender.deviceId,
        fromSignalDeviceId: sender.signalDeviceId,
        serverTs: Date.now(),
      });
      this.forwardToPartner(sender.userId, envelope);
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const attachment = ws.deserializeAttachment() as WebSocketAttachment | null;
    if (attachment) {
      this.env.DB.prepare("UPDATE users SET last_seen_at = ? WHERE id = ?")
        .bind(Date.now(), attachment.userId)
        .run()
        .catch(() => {});

      const stillOnline = this.state
        .getWebSockets()
        .some((sock) => sock !== ws && (sock.deserializeAttachment() as WebSocketAttachment | null)?.userId === attachment.userId);
      if (!stillOnline) {
        this.broadcastPresence(attachment.userId, false);
      }
    }
  }

  async webSocketError(ws: WebSocket, error: unknown): Promise<void> {
    SafeLogger.error("do_ws_error", error);
    try {
      ws.close(1011, "WebSocket error");
    } catch {
      // ignore close errors
    }
  }

  private sendToUser(targetUserId: string, serializedEnvelope: string): number {
    let deliveredCount = 0;
    for (const sock of this.state.getWebSockets()) {
      const att = sock.deserializeAttachment() as WebSocketAttachment | null;
      if (att && att.userId === targetUserId) {
        try {
          sock.send(serializedEnvelope);
          deliveredCount++;
        } catch (err) {
          SafeLogger.error("do_ws_user_send_failed", err);
        }
      }
    }
    return deliveredCount;
  }

  private forwardToPartner(senderUserId: string, serializedEnvelope: string): void {
    for (const sock of this.state.getWebSockets()) {
      const att = sock.deserializeAttachment() as WebSocketAttachment | null;
      if (att && att.userId !== senderUserId) {
        try {
          sock.send(serializedEnvelope);
        } catch (err) {
          SafeLogger.error("do_ws_send_failed", err);
        }
      }
    }
  }

  private broadcastPresence(userId: string, online: boolean): void {
    const payload = JSON.stringify({
      type: "presence",
      userId,
      online,
      ts: Date.now(),
    });
    this.forwardToPartner(userId, payload);
  }

  private async disconnectMatchingSockets(sessionId?: string, deviceId?: string): Promise<void> {
    for (const sock of this.state.getWebSockets()) {
      const att = sock.deserializeAttachment() as WebSocketAttachment | null;
      if (!att) continue;
      if ((sessionId && att.sessionId === sessionId) || (deviceId && att.deviceId === deviceId)) {
        try {
          sock.send(JSON.stringify({ type: "session_revoked" }));
          sock.close(4001, "Session revoked");
        } catch {
          // ignore
        }
      }
    }
  }
}
