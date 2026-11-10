import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from "vitest";
import { argon2id } from "@noble/hashes/argon2";
import { bytesToHex, randomBytes } from "@noble/hashes/utils";
import { createTestD1 } from "./d1Mock";
import worker from "../workers/index";
import { deriveServerVerifier } from "../shared/crypto";
import { Env } from "../shared/types";

async function hashArgon2Hex(secret: string, saltHex: string): Promise<string> {
  const saltBytes = Uint8Array.from(Buffer.from(saltHex, "hex"));
  return bytesToHex(argon2id(secret, saltBytes, { m: 65536, t: 3, p: 1, dkLen: 32 }));
}

describe("System Utility Backend — Cloudflare Realtime TURN & Call Signaling", () => {
  let db: D1Database;
  let env: Env;
  let u1Digest: string;
  let u1Salt: string;
  let u1ServerSalt: string;
  let u1Verifier: string;
  const originalFetch = globalThis.fetch;

  beforeAll(async () => {
    u1Salt = bytesToHex(randomBytes(16));
    u1ServerSalt = bytesToHex(randomBytes(16));
    u1Digest = await hashArgon2Hex("CallUserPass1!", u1Salt);
    u1Verifier = await deriveServerVerifier(u1Digest, u1ServerSalt);
  });

  beforeEach(async () => {
    db = createTestD1();
    env = {
      DB: db,
      MEDIA_BUCKET: {} as R2Bucket,
      CHAT_COORDINATOR: {
        idFromName: () => ({} as DurableObjectId),
        get: () => ({ fetch: async () => new Response(JSON.stringify({ forwarded: true, activeConnections: 1 })) } as unknown as DurableObjectStub),
      } as unknown as DurableObjectNamespace,
      ENVIRONMENT: "staging",
      ALLOWED_ORIGIN: "*",
      MAX_UPLOAD_BYTES: "52428800",
      RETENTION_DAYS: "90",
      AUTH_PEPPER: "test_pepper_for_unit_tests_only_0123456789abcdef",
      CF_TURN_KEY_ID: "turn_key_id_test_1234",
      CF_TURN_API_TOKEN: "super_secret_cf_turn_api_token_do_not_leak",
    };

    const now = Date.now();
    await db
      .prepare(
        `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind("user-1", 1, "partner_one", u1Salt, u1ServerSalt, u1Verifier, "r1", "Alex", "sig_1", now, now)
      .run();

    await db
      .prepare(
        `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind("user-2", 2, "partner_two", u1Salt, u1ServerSalt, u1Verifier, "r2", "Sam", "sig_2", now, now)
      .run();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  async function login() {
    const loginRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.3.1" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1Digest,
          deviceName: "Pixel 9",
          platform: "android",
        }),
      }),
      env
    );
    return (await loginRes.json()) as { token: string; deviceId: string };
  }

  it("reports healthy D1, R2, and Cloudflare TURN readiness on /api/v1/health", async () => {
    const healthRes = await worker.fetch(new Request("https://example.com/api/v1/health", { method: "GET" }), env);
    expect(healthRes.status).toBe(200);
    const body = (await healthRes.json()) as {
      status: string;
      environment: string;
      checks: { d1: string; r2: string; turnConfigured: boolean; firebaseExcluded: boolean; driveBackupDeferred: boolean };
    };
    expect(body.status).toBe("HEALTHY");
    expect(body.environment).toBe("staging");
    expect(body.checks.d1).toBe("ok");
    expect(body.checks.r2).toBe("bound");
    expect(body.checks.turnConfigured).toBe(true);
    expect(body.checks.firebaseExcluded).toBe(true);
    expect(body.checks.driveBackupDeferred).toBe(true);
  });

  it("mints ephemeral ICE credentials from Cloudflare Realtime TURN API and persists call logs", async () => {
    const { token, deviceId } = await login();

    const mockFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe(
        "https://rtc.live.cloudflare.com/v1/turn/keys/turn_key_id_test_1234/credentials/generate-ice-servers"
      );
      expect(init?.method).toBe("POST");
      const headers = init?.headers as Record<string, string>;
      expect(headers["Authorization"]).toBe("Bearer super_secret_cf_turn_api_token_do_not_leak");

      return new Response(
        JSON.stringify({
          iceServers: [
            { urls: ["stun:stun.cloudflare.com:3478"] },
            {
              urls: [
                "turn:turn.cloudflare.com:3478?transport=udp",
                "turn:turn.cloudflare.com:3478?transport=tcp",
                "turns:turn.cloudflare.com:5349?transport=tcp",
              ],
              username: "cf_ephemeral_user",
              credential: "cf_ephemeral_cred",
            },
          ],
        }),
        { status: 201, headers: { "Content-Type": "application/json" } }
      );
    });
    globalThis.fetch = mockFetch as unknown as typeof fetch;

    const turnRes = await worker.fetch(
      new Request("https://example.com/api/v1/turn-credentials", {
        method: "GET",
        headers: { Authorization: `Bearer ${token}`, "X-Device-Id": deviceId },
      }),
      env
    );
    expect(turnRes.status).toBe(200);
    const rawText = await turnRes.text();
    expect(rawText).not.toContain("super_secret_cf_turn_api_token_do_not_leak");

    const turn = JSON.parse(rawText) as {
      provider: string;
      ttlSeconds: number;
      expiresAt: number;
      iceServers: Array<{ urls: string[]; username?: string; credential?: string }>;
    };
    expect(turn.provider).toBe("cloudflare_realtime_turn");
    expect(turn.ttlSeconds).toBe(3600);
    expect(turn.expiresAt).toBeGreaterThan(Date.now());
    expect(turn.iceServers).toHaveLength(2);
    expect(turn.iceServers[1]!.urls).toContain("turns:turn.cloudflare.com:5349?transport=tcp");
    expect(turn.iceServers[1]!.username).toBe("cf_ephemeral_user");

    const callId = "22222222-2222-4222-8222-222222222222";
    const logRes = await worker.fetch(
      new Request("https://example.com/api/v1/calls/log", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`,
          "X-Device-Id": deviceId,
        },
        body: JSON.stringify({
          id: callId,
          calleeUserId: "user-2",
          callType: "video",
          status: "INITIATED",
          startedAt: Date.now(),
        }),
      }),
      env
    );
    expect(logRes.status).toBe(200);
  });

  it("handles missing TURN secrets (503) and upstream TURN API rejection (502) cleanly", async () => {
    const { token, deviceId } = await login();

    const envNoTurn: Env = { ...env, CF_TURN_KEY_ID: undefined, CF_TURN_API_TOKEN: undefined };
    const res503 = await worker.fetch(
      new Request("https://example.com/api/v1/turn-credentials", {
        method: "GET",
        headers: { Authorization: `Bearer ${token}`, "X-Device-Id": deviceId },
      }),
      envNoTurn
    );
    expect(res503.status).toBe(503);
    const err503 = (await res503.json()) as { error: { code: string } };
    expect(err503.error.code).toBe("TURN_NOT_CONFIGURED");

    globalThis.fetch = vi.fn(async () => new Response("Unauthorized", { status: 401 })) as unknown as typeof fetch;
    const res502 = await worker.fetch(
      new Request("https://example.com/api/v1/turn-credentials", {
        method: "GET",
        headers: { Authorization: `Bearer ${token}`, "X-Device-Id": deviceId },
      }),
      env
    );
    expect(res502.status).toBe(502);
    const text502 = await res502.text();
    expect(text502).not.toContain("super_secret_cf_turn_api_token_do_not_leak");
  });
});
