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

describe("System Utility Backend — Auth, Owner Device Approval, Audit & Two-User Isolation", () => {
  let db: D1Database;
  let env: Env;

  let u1SaltHex: string;
  let u1ServerSaltHex: string;
  let u1ClientDigest: string;
  let u1Verifier: string;
  let u1RecoveryCode: string;
  let u1RecDigest: string;
  let u1RecVerifier: string;

  let u2SaltHex: string;
  let u2ServerSaltHex: string;
  let u2Verifier: string;
  let u2RecVerifier: string;

  let newSaltHex: string;
  let newDigestHex: string;

  beforeAll(async () => {
    u1SaltHex = bytesToHex(randomBytes(16));
    u1ServerSaltHex = bytesToHex(randomBytes(16));
    u1ClientDigest = await hashArgon2Hex("StrongCouplePass1!", u1SaltHex);
    u1Verifier = await deriveServerVerifier(u1ClientDigest, u1ServerSaltHex);

    u1RecoveryCode = "AAAA-BBBB-CCCC-DDDD";
    u1RecDigest = await hashArgon2Hex(u1RecoveryCode, u1SaltHex);
    u1RecVerifier = await deriveServerVerifier(u1RecDigest, u1ServerSaltHex);

    u2SaltHex = bytesToHex(randomBytes(16));
    u2ServerSaltHex = bytesToHex(randomBytes(16));
    const u2ClientDigest = await hashArgon2Hex("StrongCouplePass2!", u2SaltHex);
    u2Verifier = await deriveServerVerifier(u2ClientDigest, u2ServerSaltHex);
    const u2RecDigest = await hashArgon2Hex("1111-2222-3333-4444", u2SaltHex);
    u2RecVerifier = await deriveServerVerifier(u2RecDigest, u2ServerSaltHex);

    newSaltHex = bytesToHex(randomBytes(16));
    newDigestHex = await hashArgon2Hex("BrandNewSecretPass99!", newSaltHex);
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
      .bind("user-1", 1, "partner_one", u1SaltHex, u1ServerSaltHex, u1Verifier, u1RecVerifier, "Alex", "sig_1", now, now)
      .run();

    await db
      .prepare(
        `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .bind("user-2", 2, "partner_two", u2SaltHex, u2ServerSaltHex, u2Verifier, u2RecVerifier, "Sam", "sig_2", now, now)
      .run();

    await db
      .prepare("INSERT INTO conversations (id, user_one_id, user_two_id, created_at) VALUES ('primary_pair', 'user-1', 'user-2', ?)")
      .bind(now)
      .run();
  });

  it("enforces two-user hard limit trigger in D1", async () => {
    const now = Date.now();
    await expect(
      db
        .prepare(
          `INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, encryption_identity_id, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .bind("user-3", 1, "intruder", u1SaltHex, u1ServerSaltHex, "abc", "def", "Intruder", "sig_3", now, now)
        .run()
    ).rejects.toThrow();
  });

  it("prevents username enumeration with deterministic fake salts", async () => {
    const makeReq = () =>
      new Request("https://example.com/api/v1/auth/salt", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.1" },
        body: JSON.stringify({ username: "nonexistent_user" }),
      });

    const res1 = await worker.fetch(makeReq(), env);
    expect(res1.status).toBe(200);
    const data1 = (await res1.json()) as { saltHex: string };
    expect(data1.saltHex).toMatch(/^[a-f0-9]{32}$/);

    const res2 = await worker.fetch(makeReq(), env);
    const data2 = (await res2.json()) as { saltHex: string };
    expect(data2.saltHex).toBe(data1.saltHex);
  });

  it("authenticates predefined user via Argon2id + PBKDF2 and rejects revoked sessions", async () => {
    const saltRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/salt", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.2" },
        body: JSON.stringify({ username: "partner_one" }),
      }),
      env
    );
    const { saltHex } = (await saltRes.json()) as { saltHex: string };
    expect(saltHex).toBe(u1SaltHex);

    const loginRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.2" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceName: "Pixel 9 Pro",
          platform: "android",
        }),
      }),
      env
    );
    expect(loginRes.status).toBe(200);
    const session = (await loginRes.json()) as {
      token: string;
      sessionId: string;
      deviceId: string;
      partnerUser: { username: string };
    };
    expect(session.partnerUser.username).toBe("partner_two");

    // Verify authenticated profile access
    const profileRes = await worker.fetch(
      new Request("https://example.com/api/v1/profile", {
        method: "GET",
        headers: { Authorization: `Bearer ${session.token}`, "X-Device-Id": session.deviceId },
      }),
      env
    );
    expect(profileRes.status).toBe(200);

    // Revoke session cleanly
    const revokeRes = await worker.fetch(
      new Request(`https://example.com/api/v1/auth/sessions/${session.sessionId}/revoke`, {
        method: "POST",
        headers: { Authorization: `Bearer ${session.token}`, "X-Device-Id": session.deviceId },
      }),
      env
    );
    expect(revokeRes.status).toBe(200);

    // Subsequent API access with revoked session MUST fail with 401
    const afterRevokeRes = await worker.fetch(
      new Request("https://example.com/api/v1/profile", {
        method: "GET",
        headers: { Authorization: `Bearer ${session.token}`, "X-Device-Id": session.deviceId },
      }),
      env
    );
    expect(afterRevokeRes.status).toBe(401);
  });

  it("requires owner approval for second device enrollment and logs audit events", async () => {
    const dev1Uuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const dev2Uuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    const dev3Uuid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

    // First device bootstraps automatically as owner
    const login1Res = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.10" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceId: dev1Uuid,
          deviceName: "Primary Phone",
          platform: "android",
        }),
      }),
      env
    );
    expect(login1Res.status).toBe(200);
    const owner = (await login1Res.json()) as { token: string; deviceId: string };
    expect(owner.deviceId).toBe(dev1Uuid);

    // Second device requires explicit owner approval
    const login2Pending = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.11" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceId: dev2Uuid,
          deviceName: "Tablet Two",
          platform: "android",
        }),
      }),
      env
    );
    expect(login2Pending.status).toBe(403);
    const pendingJson = (await login2Pending.json()) as {
      error: { code: string; pendingDeviceId?: string };
    };
    expect(pendingJson.error.code).toBe("DEVICE_APPROVAL_REQUIRED");
    expect(pendingJson.error.pendingDeviceId).toBe(dev2Uuid);

    // Owner inspects pending devices in /sessions
    const sessionsRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/sessions", {
        method: "GET",
        headers: { Authorization: `Bearer ${owner.token}`, "X-Device-Id": owner.deviceId },
      }),
      env
    );
    expect(sessionsRes.status).toBe(200);
    const sessionsBody = (await sessionsRes.json()) as {
      pendingDevices: Array<{ deviceId: string; deviceName: string; approvalStatus: string }>;
    };
    expect(sessionsBody.pendingDevices).toHaveLength(1);
    expect(sessionsBody.pendingDevices[0]!.deviceId).toBe(dev2Uuid);
    expect(sessionsBody.pendingDevices[0]!.approvalStatus).toBe("PENDING");

    // Owner approves Device 2
    const approveRes = await worker.fetch(
      new Request(`https://example.com/api/v1/auth/devices/${dev2Uuid}/approve`, {
        method: "POST",
        headers: { Authorization: `Bearer ${owner.token}`, "X-Device-Id": owner.deviceId },
      }),
      env
    );
    expect(approveRes.status).toBe(200);

    // Device 2 retries login and is now granted a session token
    const login2Success = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.11" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceId: dev2Uuid,
          deviceName: "Tablet Two",
          platform: "android",
        }),
      }),
      env
    );
    expect(login2Success.status).toBe(200);

    // Third device requests enrollment and owner rejects it
    const login3Pending = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.12" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceId: dev3Uuid,
          deviceName: "Unknown Phone",
          platform: "android",
        }),
      }),
      env
    );
    expect(login3Pending.status).toBe(403);

    const rejectRes = await worker.fetch(
      new Request(`https://example.com/api/v1/auth/devices/${dev3Uuid}/reject`, {
        method: "POST",
        headers: { Authorization: `Bearer ${owner.token}`, "X-Device-Id": owner.deviceId },
      }),
      env
    );
    expect(rejectRes.status).toBe(200);

    // Subsequent login attempt using rejected deviceId returns DEVICE_REVOKED
    const login3AfterReject = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.12" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceId: dev3Uuid,
          deviceName: "Unknown Phone",
          platform: "android",
        }),
      }),
      env
    );
    expect(login3AfterReject.status).toBe(403);
    const rejectedErr = (await login3AfterReject.json()) as { error: { code: string } };
    expect(rejectedErr.error.code).toBe("DEVICE_REVOKED");

    // Audit log contains security events
    const auditRes = await worker.fetch(
      new Request("https://example.com/api/v1/security/audit", {
        method: "GET",
        headers: { Authorization: `Bearer ${owner.token}`, "X-Device-Id": owner.deviceId },
      }),
      env
    );
    expect(auditRes.status).toBe(200);
    const auditData = (await auditRes.json()) as { events: Array<{ eventType: string }> };
    const types = auditData.events.map((e) => e.eventType);
    expect(types).toContain("DEVICE_BOOTSTRAP_APPROVED");
    expect(types).toContain("DEVICE_ENROLLMENT_PENDING");
    expect(types).toContain("DEVICE_APPROVED");
    expect(types).toContain("DEVICE_REJECTED");
  });

  it("enforces login rate limiting and lockout after 5 wrong password attempts", async () => {
    const wrongDigest = "a".repeat(64);
    for (let i = 0; i < 4; i++) {
      const res = await worker.fetch(
        new Request("https://example.com/api/v1/auth/login", {
          method: "POST",
          headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.99" },
          body: JSON.stringify({
            username: "partner_one",
            clientDigestHex: wrongDigest,
            deviceName: "Attacker",
            platform: "android",
          }),
        }),
        env
      );
      expect(res.status).toBe(401);
    }

    const fifthRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.99" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: wrongDigest,
          deviceName: "Attacker",
          platform: "android",
        }),
      }),
      env
    );
    expect(fifthRes.status).toBe(429);
  });

  it("rotates password via account recovery code and revokes all prior sessions and devices", async () => {
    const loginRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.5" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: u1ClientDigest,
          deviceName: "OldPhone",
          platform: "android",
        }),
      }),
      env
    );
    const oldSession = (await loginRes.json()) as { token: string; deviceId: string };

    const recoverRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/recover", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.6" },
        body: JSON.stringify({
          username: "partner_one",
          recoveryDigestHex: u1RecDigest,
          newSaltHex: newSaltHex,
          newClientDigestHex: newDigestHex,
        }),
      }),
      env
    );
    expect(recoverRes.status).toBe(200);

    // Old session was revoked cleanly
    const oldCheck = await worker.fetch(
      new Request("https://example.com/api/v1/profile", {
        method: "GET",
        headers: { Authorization: `Bearer ${oldSession.token}`, "X-Device-Id": oldSession.deviceId },
      }),
      env
    );
    expect(oldCheck.status).toBe(401);

    // New password authenticates cleanly on replacement device
    const newLoginRes = await worker.fetch(
      new Request("https://example.com/api/v1/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", "CF-Connecting-IP": "10.0.0.6" },
        body: JSON.stringify({
          username: "partner_one",
          clientDigestHex: newDigestHex,
          deviceName: "NewPhone",
          platform: "android",
        }),
      }),
      env
    );
    expect(newLoginRes.status).toBe(200);
  });
});
