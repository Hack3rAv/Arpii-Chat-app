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

describe("System Utility Backend — Profile Avatar & Backup Manifest Routes", () => {
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
    u1Digest = await hashArgon2Hex("BackupPass1!", u1Salt);
    u1Verifier = await deriveServerVerifier(u1Digest, u1ServerSalt);

    u2Salt = bytesToHex(randomBytes(16));
    u2ServerSalt = bytesToHex(randomBytes(16));
    u2Digest = await hashArgon2Hex("BackupPass2!", u2Salt);
    u2Verifier = await deriveServerVerifier(u2Digest, u2ServerSalt);
  });

  beforeEach(async () => {
    db = createTestD1();
    env = {
      DB: db,
      MEDIA_BUCKET: {} as R2Bucket,
      CHAT_COORDINATOR: {
        idFromName: () => ({} as DurableObjectId),
        get: () => ({ fetch: async () => new Response("{}") } as unknown as DurableObjectStub),
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

  it("persists encrypted avatar key fields on profile update and isolates backup manifests per user", async () => {
    const u1 = await loginAs("partner_one", u1Digest, "10.0.3.1");
    const u2 = await loginAs("partner_two", u2Digest, "10.0.3.2");

    // 1. Update profile + avatar metadata
    const patchRes = await worker.fetch(
      new Request("https://example.com/api/v1/profile", {
        method: "PATCH",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: JSON.stringify({
          displayName: "Alex Updated",
          about: "Private status",
          avatarR2Key: "enc/user-1/avatar_1.bin",
          avatarKeyBase64: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        }),
      }),
      env
    );
    expect(patchRes.status).toBe(200);
    const patchJson = (await patchRes.json()) as {
      selfUser: { displayName: string; avatarR2Key: string; avatarKeyBase64: string };
    };
    expect(patchJson.selfUser.displayName).toBe("Alex Updated");
    expect(patchJson.selfUser.avatarR2Key).toBe("enc/user-1/avatar_1.bin");

    // 2. Partner observes updated avatar reference on profile fetch
    const p2ProfileRes = await worker.fetch(
      new Request("https://example.com/api/v1/profile", {
        method: "GET",
        headers: {
          Authorization: `Bearer ${u2.token}`,
          "X-Device-Id": u2.deviceId,
        },
      }),
      env
    );
    expect(p2ProfileRes.status).toBe(200);
    const p2ProfileJson = (await p2ProfileRes.json()) as {
      partnerUser: { displayName: string; avatarR2Key: string; avatarKeyBase64: string };
    };
    expect(p2ProfileJson.partnerUser.displayName).toBe("Alex Updated");
    expect(p2ProfileJson.partnerUser.avatarR2Key).toBe("enc/user-1/avatar_1.bin");
    expect(p2ProfileJson.partnerUser.avatarKeyBase64).toBe("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    // 3. Record backup manifest for user 1
    const shaHex = "a".repeat(64);
    const postManifest = await worker.fetch(
      new Request("https://example.com/api/v1/backups/manifest", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${u1.token}`,
          "X-Device-Id": u1.deviceId,
        },
        body: JSON.stringify({
          driveFileId: "drive_file_123",
          versionTag: "v1_test",
          sizeBytes: 4096,
          sha256Ciphertext: shaHex,
        }),
      }),
      env
    );
    expect(postManifest.status).toBe(200);

    // 4. User 1 sees their manifest; User 2 sees zero of User 1's manifests
    const u1List = await worker.fetch(
      new Request("https://example.com/api/v1/backups/manifest", {
        method: "GET",
        headers: { Authorization: `Bearer ${u1.token}`, "X-Device-Id": u1.deviceId },
      }),
      env
    );
    const u1Data = (await u1List.json()) as { manifests: Array<{ driveFileId: string }> };
    expect(u1Data.manifests.length).toBe(1);
    expect(u1Data.manifests[0]!.driveFileId).toBe("drive_file_123");

    const u2List = await worker.fetch(
      new Request("https://example.com/api/v1/backups/manifest", {
        method: "GET",
        headers: { Authorization: `Bearer ${u2.token}`, "X-Device-Id": u2.deviceId },
      }),
      env
    );
    const u2Data = (await u2List.json()) as { manifests: Array<{ driveFileId: string }> };
    expect(u2Data.manifests.length).toBe(0);
  });
});
