import { describe, it, expect, beforeAll, beforeEach } from "vitest";
import { argon2id } from "@noble/hashes/argon2";
import { bytesToHex, randomBytes } from "@noble/hashes/utils";
import { createTestD1 } from "./d1Mock";
import worker from "../workers/index";
import { deriveServerVerifier, sha256Hex } from "../shared/crypto";
import { Env } from "../shared/types";

async function hashArgon2Hex(secret: string, saltHex: string): Promise<string> {
  const saltBytes = Uint8Array.from(Buffer.from(saltHex, "hex"));
  return bytesToHex(argon2id(secret, saltBytes, { m: 65536, t: 3, p: 1, dkLen: 32 }));
}

function createMemoryR2(): R2Bucket {
  const store = new Map<string, Uint8Array>();
  return {
    async put(key: string, value: ArrayBuffer | Uint8Array | string) {
      const bytes =
        typeof value === "string"
          ? new TextEncoder().encode(value)
          : value instanceof Uint8Array
            ? new Uint8Array(value)
            : new Uint8Array(value);
      store.set(key, bytes);
      return { key, size: bytes.byteLength } as R2Object;
    },
    async get(key: string) {
      const bytes = store.get(key);
      if (!bytes) return null;
      return {
        key,
        size: bytes.byteLength,
        body: new ReadableStream({
          start(controller) {
            controller.enqueue(bytes);
            controller.close();
          },
        }),
        async arrayBuffer() {
          return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
        },
      } as unknown as R2ObjectBody;
    },
  } as unknown as R2Bucket;
}

describe("System Utility Backend — Encrypted Media R2 Storage & Short-Lived Grants", () => {
  let db: D1Database;
  let env: Env;
  let u1Digest: string;
  let u2Digest: string;
  let u1Salt: string;
  let u1ServerSalt: string;
  let u1Verifier: string;
  let u2Salt: string;
  let u2ServerSalt: string;
  let u2Verifier: string;

  beforeAll(async () => {
    u1Salt = bytesToHex(randomBytes(16));
    u1ServerSalt = bytesToHex(randomBytes(16));
    u1Digest = await hashArgon2Hex("MediaPass1!", u1Salt);
    u1Verifier = await deriveServerVerifier(u1Digest, u1ServerSalt);

    u2Salt = bytesToHex(randomBytes(16));
    u2ServerSalt = bytesToHex(randomBytes(16));
    u2Digest = await hashArgon2Hex("MediaPass2!", u2Salt);
    u2Verifier = await deriveServerVerifier(u2Digest, u2ServerSalt);
  });

  beforeEach(async () => {
    db = createTestD1();
    env = {
      DB: db,
      MEDIA_BUCKET: createMemoryR2(),
      CHAT_COORDINATOR: {
        idFromName: () => ({} as DurableObjectId),
        get: () => ({ fetch: async () => new Response("{}") } as unknown as DurableObjectStub),
      } as unknown as DurableObjectNamespace,
      ENVIRONMENT: "test",
      ALLOWED_ORIGIN: "*",
      MAX_UPLOAD_BYTES: "1024",
      RETENTION_DAYS: "90",
      AUTH_PEPPER: "test_pepper_for_unit_tests_only_0123456789abcdef",
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
      .bind("user-2", 2, "partner_two", u2Salt, u2ServerSalt, u2Verifier, "r2", "Sam", "sig_2", now, now)
      .run();
  });

  async function loginAs(username: string, clientDigestHex: string, ip: string) {
    const res = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": ip },
        body: JSON.stringify({ username, clientDigestHex, deviceName: "Phone", platform: "android" }),
      }),
      env
    );
    return (await res.json()) as { token: string; deviceId: string };
  }

  it("enforces upload cap, SHA-256 integrity, upload grant expiry, and short-lived download grants", async () => {
    const u1 = await loginAs("partner_one", u1Digest, "10.0.2.1");
    const u2 = await loginAs("partner_two", u2Digest, "10.0.2.2");

    const ciphertext = randomBytes(256);
    const expectedSha = await sha256Hex(ciphertext);

    // Reject oversized upload (>1024 test cap)
    const bigRes = await worker.fetch(
      new Request("https://example.com/api/v1/media/init-upload", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: JSON.stringify({
          encryptedSizeBytes: 5000,
          sha256Ciphertext: expectedSha,
          mimeCategory: "image",
        }),
      }),
      env
    );
    expect(bigRes.status).toBe(413);

    // Init valid upload
    const initRes = await worker.fetch(
      new Request("https://example.com/api/v1/media/init-upload", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: JSON.stringify({
          encryptedSizeBytes: ciphertext.byteLength,
          sha256Ciphertext: expectedSha,
          mimeCategory: "voice",
        }),
      }),
      env
    );
    expect(initRes.status).toBe(200);
    const { mediaId, r2ObjectKey, uploadExpiresAt } = (await initRes.json()) as {
      mediaId: string;
      r2ObjectKey: string;
      uploadExpiresAt: number;
    };
    expect(uploadExpiresAt).toBeGreaterThan(Date.now());

    // Reject tampered blob with mismatched SHA-256
    const badPut = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${mediaId}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: randomBytes(256),
      }),
      env
    );
    expect(badPut.status).toBe(400);

    // Put matching ciphertext blob
    const putRes = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${mediaId}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: ciphertext,
      }),
      env
    );
    expect(putRes.status).toBe(200);

    // Direct GET without short-lived download grant is rejected (403)
    const noGrantRes = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${r2ObjectKey}`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${u2.token}`,
          "X-Device-Id": u2.deviceId,
        },
      }),
      env
    );
    expect(noGrantRes.status).toBe(403);

    // Forged grant signature is rejected (403)
    const forgedGrantRes = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${r2ObjectKey}?grant=${Date.now() + 60000}.${"f".repeat(64)}`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${u2.token}`,
          "X-Device-Id": u2.deviceId,
        },
      }),
      env
    );
    expect(forgedGrantRes.status).toBe(403);

    // Partner authorizes short-lived download grant
    const authDlRes = await worker.fetch(
      new Request("https://example.com/api/v1/media/authorize-download", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u2.token}`,
          "X-Device-Id": u2.deviceId,
        },
        body: JSON.stringify({ r2ObjectKey }),
      }),
      env
    );
    expect(authDlRes.status).toBe(200);
    const { grant, expiresAt } = (await authDlRes.json()) as { grant: string; expiresAt: number };
    expect(expiresAt).toBeGreaterThan(Date.now());

    // Partner downloads exact encrypted bytes with authorized grant
    const dlRes = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${r2ObjectKey}?grant=${encodeURIComponent(grant)}`, {
        method: "GET",
        headers: {
          Authorization: `Bearer ${u2.token}`,
          "X-Device-Id": u2.deviceId,
        },
      }),
      env
    );
    expect(dlRes.status).toBe(200);
    const downloaded = new Uint8Array(await dlRes.arrayBuffer());
    expect(downloaded).toEqual(ciphertext);

    // Unauthenticated download is denied (401)
    const unauth = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${r2ObjectKey}?grant=${encodeURIComponent(grant)}`, {
        method: "GET",
      }),
      env
    );
    expect(unauth.status).toBe(401);

    // Expired upload window is rejected (403 UPLOAD_GRANT_EXPIRED)
    const expiredInit = await worker.fetch(
      new Request("https://example.com/api/v1/media/init-upload", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: JSON.stringify({
          encryptedSizeBytes: ciphertext.byteLength,
          sha256Ciphertext: expectedSha,
          mimeCategory: "image",
        }),
      }),
      env
    );
    const { mediaId: expiredId } = (await expiredInit.json()) as { mediaId: string };
    await db
      .prepare("UPDATE encrypted_media_objects SET upload_expires_at = ? WHERE id = ?")
      .bind(Date.now() - 10_000, expiredId)
      .run();

    const expiredPut = await worker.fetch(
      new Request(`https://example.com/api/v1/media/blob/${expiredId}`, {
        method: "PUT",
        headers: {
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: ciphertext,
      }),
      env
    );
    expect(expiredPut.status).toBe(403);
    const expiredBody = (await expiredPut.json()) as { error: { code: string } };
    expect(expiredBody.error.code).toBe("UPLOAD_GRANT_EXPIRED");
  });
});
