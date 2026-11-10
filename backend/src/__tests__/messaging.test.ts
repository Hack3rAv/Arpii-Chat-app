import { describe, it, expect, beforeAll, beforeEach } from "vitest";
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

describe("System Utility Backend — Phase 2 Key Directory & E2EE Messaging", () => {
  let db: D1Database;
  let env: Env;
  let u1Digest: string;
  let u2Digest: string;
  let u1SaltHex: string;
  let u1ServerSaltHex: string;
  let u1Verifier: string;
  let u2SaltHex: string;
  let u2ServerSaltHex: string;
  let u2Verifier: string;
  let doBroadcasts: Array<{ targetUserId: string; payload: unknown }>;

  beforeAll(async () => {
    u1SaltHex = bytesToHex(randomBytes(16));
    u1ServerSaltHex = bytesToHex(randomBytes(16));
    u1Digest = await hashArgon2Hex("AlphaUserPass#1", u1SaltHex);
    u1Verifier = await deriveServerVerifier(u1Digest, u1ServerSaltHex);

    u2SaltHex = bytesToHex(randomBytes(16));
    u2ServerSaltHex = bytesToHex(randomBytes(16));
    u2Digest = await hashArgon2Hex("BetaUserPass#2", u2SaltHex);
    u2Verifier = await deriveServerVerifier(u2Digest, u2ServerSaltHex);
  });

  beforeEach(async () => {
    db = createTestD1();
    doBroadcasts = [];
    env = {
      DB: db,
      MEDIA_BUCKET: {} as R2Bucket,
      CHAT_COORDINATOR: {
        idFromName: () => ({} as DurableObjectId),
        get: () =>
          ({
            fetch: async (_url: string, init?: RequestInit) => {
              if (init?.body && typeof init.body === "string") {
                try {
                  doBroadcasts.push(JSON.parse(init.body) as { targetUserId: string; payload: unknown });
                } catch {
                  // ignore
                }
              }
              return new Response("{}");
            },
          } as unknown as DurableObjectStub),
      } as unknown as DurableObjectNamespace,
      ENVIRONMENT: "test",
      ALLOWED_ORIGIN: "*",
      MAX_UPLOAD_BYTES: "52428800",
      RETENTION_DAYS: "90",
      AUTH_PEPPER: "test_pepper_for_unit_tests_only_0123456789abcdef",
    };

    const now = Date.now();
    await db
      .prepare(
        `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind("user-1", 1, "partner_one", u1SaltHex, u1ServerSaltHex, u1Verifier, "rec1", "Alex", "sig_1", now, now)
      .run();

    await db
      .prepare(
        `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind("user-2", 2, "partner_two", u2SaltHex, u2ServerSaltHex, u2Verifier, "rec2", "Sam", "sig_2", now, now)
      .run();
  });

  async function loginAs(username: string, clientDigestHex: string, ip: string) {
    const res = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
        body: JSON.stringify({
          username,
          clientDigestHex,
          deviceName: `${username}-phone`,
          platform: "android",
        }),
      }),
      env
    );
    expect(res.status).toBe(200);
    return (await res.json()) as { token: string; deviceId: string };
  }

  it("uploads Signal/PQXDH bundle and atomically pops partner prekeys", async () => {
    const user1 = await loginAs("partner_one", u1Digest, "10.0.1.1");
    const user2 = await loginAs("partner_two", u2Digest, "10.0.1.2");

    // User 2 uploads identity, signed prekey, 2 EC OTPKs, 1 Kyber OTPK + last resort Kyber
    const uploadRes = await worker.fetch(
      new Request("https://example.com/api/v1/keys/bundle", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user2.token}`,
          "X-Device-Id": user2.deviceId,
        },
        body: JSON.stringify({
          registrationId: 12345,
          identityKeyB64: "B".repeat(44),
          signedPreKeyId: 1,
          signedPreKeyB64: "S".repeat(44),
          signedPreKeySignatureB64: "G".repeat(88),
          kyberLastResortId: 9001,
          kyberLastResortB64: "L".repeat(128),
          kyberLastResortSigB64: "K".repeat(88),
          oneTimePreKeys: [
            { keyId: 10, publicKeyB64: "O".repeat(44) },
            { keyId: 11, publicKeyB64: "P".repeat(44) },
          ],
          kyberPreKeys: [{ keyId: 100, publicKeyB64: "Q".repeat(128), signatureB64: "R".repeat(88) }],
        }),
      }),
      env
    );
    expect(uploadRes.status).toBe(200);

    // First fetch pops EC OTPK 10 and Kyber OTPK 100
    const fetch1 = await worker.fetch(
      new Request("https://example.com/api/v1/keys/partner", {
        method: "GET",
        headers: { Authorization: `Bearer ${user1.token}`, "X-Device-Id": user1.deviceId },
      }),
      env
    );
    expect(fetch1.status).toBe(200);
    const b1 = (await fetch1.json()) as {
      oneTimePreKey: { keyId: number } | null;
      kyberPreKey: { keyId: number };
    };
    expect(b1.oneTimePreKey?.keyId).toBe(10);
    expect(b1.kyberPreKey.keyId).toBe(100);

    // Second fetch pops EC OTPK 11 and falls back to Kyber last resort 9001
    const fetch2 = await worker.fetch(
      new Request("https://example.com/api/v1/keys/partner", {
        method: "GET",
        headers: { Authorization: `Bearer ${user1.token}`, "X-Device-Id": user1.deviceId },
      }),
      env
    );
    const b2 = (await fetch2.json()) as {
      oneTimePreKey: { keyId: number } | null;
      kyberPreKey: { keyId: number };
    };
    expect(b2.oneTimePreKey?.keyId).toBe(11);
    expect(b2.kyberPreKey.keyId).toBe(9001);

    // Third fetch has null EC OTPK and still provides Kyber last resort
    const fetch3 = await worker.fetch(
      new Request("https://example.com/api/v1/keys/partner", {
        method: "GET",
        headers: { Authorization: `Bearer ${user1.token}`, "X-Device-Id": user1.deviceId },
      }),
      env
    );
    const b3 = (await fetch3.json()) as {
      oneTimePreKey: { keyId: number } | null;
      kyberPreKey: { keyId: number };
    };
    expect(b3.oneTimePreKey).toBeNull();
    expect(b3.kyberPreKey.keyId).toBe(9001);
  });

  it("sends ciphertext envelopes, deduplicates retries, syncs incrementally, updates receipts, edits, and deletes", async () => {
    const user1 = await loginAs("partner_one", u1Digest, "10.0.1.3");
    const user2 = await loginAs("partner_two", u2Digest, "10.0.1.4");
    const msgId = "11111111-1111-4111-8111-111111111111";

    // Reject arbitrary third-party recipient
    const forbiddenRes = await worker.fetch(
      new Request("https://example.com/api/v1/messages/send", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user1.token}`,
          "X-Device-Id": user1.deviceId,
        },
        body: JSON.stringify({
          id: msgId,
          recipientUserId: "user-3-stranger",
          messageType: "signal_prekey",
          ciphertextB64: "U0lHTkFMX0NJUEhFUg==",
        }),
      }),
      env
    );
    expect(forbiddenRes.status).toBe(403);

    // Send ciphertext from user1 -> user2
    const sendRes = await worker.fetch(
      new Request("https://example.com/api/v1/messages/send", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user1.token}`,
          "X-Device-Id": user1.deviceId,
        },
        body: JSON.stringify({
          id: msgId,
          recipientUserId: "user-2",
          messageType: "signal_prekey",
          ciphertextB64: "U0lHTkFMX0NJUEhFUg==",
        }),
      }),
      env
    );
    expect(sendRes.status).toBe(200);
    const sent = (await sendRes.json()) as { id: string; serverSeq: number; receiptStatus: string };
    expect(sent.serverSeq).toBe(1);
    expect(sent.receiptStatus).toBe("SENT");

    // Duplicate retry returns exact existing message without incrementing seq
    const retryRes = await worker.fetch(
      new Request("https://example.com/api/v1/messages/send", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user1.token}`,
          "X-Device-Id": user1.deviceId,
        },
        body: JSON.stringify({
          id: msgId,
          recipientUserId: "user-2",
          messageType: "signal_prekey",
          ciphertextB64: "U0lHTkFMX0NJUEhFUg==",
        }),
      }),
      env
    );
    const retried = (await retryRes.json()) as { serverSeq: number };
    expect(retried.serverSeq).toBe(1);

    // User 2 syncs afterSeq=0
    const syncRes = await worker.fetch(
      new Request("https://example.com/api/v1/messages/sync?afterSeq=0", {
        method: "GET",
        headers: { Authorization: `Bearer ${user2.token}`, "X-Device-Id": user2.deviceId },
      }),
      env
    );
    expect(syncRes.status).toBe(200);
    const synced = (await syncRes.json()) as {
      messages: Array<{ id: string; ciphertextB64: string; receiptStatus: string }>;
      maxSeq: number;
    };
    expect(synced.messages).toHaveLength(1);
    expect(synced.messages[0]!.ciphertextB64).toBe("U0lHTkFMX0NJUEhFUg==");
    expect(synced.maxSeq).toBe(1);

    // User 2 acknowledges READ
    const ackRes = await worker.fetch(
      new Request("https://example.com/api/v1/messages/ack", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user2.token}`,
          "X-Device-Id": user2.deviceId,
        },
        body: JSON.stringify({ messageIds: [msgId], status: "READ" }),
      }),
      env
    );
    expect(ackRes.status).toBe(200);

    // User 1 edits message
    const editRes = await worker.fetch(
      new Request(`https://example.com/api/v1/messages/${msgId}/edit`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${user1.token}`,
          "X-Device-Id": user1.deviceId,
        },
        body: JSON.stringify({
          recipientUserId: "user-2",
          messageType: "edit_envelope",
          ciphertextB64: "RURJVEVEX0NJUEhFUg==",
        }),
      }),
      env
    );
    expect(editRes.status).toBe(200);
    const edited = (await editRes.json()) as { isEdited: boolean; serverSeq: number; receiptStatus: string };
    expect(edited.isEdited).toBe(true);
    expect(edited.serverSeq).toBe(2);
    expect(edited.receiptStatus).toBe("READ");

    // User 1 deletes message for everyone (clears ciphertext on server)
    const delRes = await worker.fetch(
      new Request(`https://example.com/api/v1/messages/${msgId}/delete`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${user1.token}`,
          "X-Device-Id": user1.deviceId,
        },
      }),
      env
    );
    expect(delRes.status).toBe(200);
    const deleted = (await delRes.json()) as { isDeletedForEveryone: boolean; ciphertextB64: string; serverSeq: number };
    expect(deleted.isDeletedForEveryone).toBe(true);
    expect(deleted.ciphertextB64).toBe("");
    expect(deleted.serverSeq).toBe(3);

    // Confirm DO notifications were dispatched
    expect(doBroadcasts.length).toBeGreaterThanOrEqual(4);
  });
});
