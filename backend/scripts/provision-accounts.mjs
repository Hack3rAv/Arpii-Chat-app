#!/usr/bin/env node
import { argon2id } from "@noble/hashes/argon2";
import { bytesToHex, randomBytes } from "@noble/hashes/utils";
import { webcrypto } from "node:crypto";
import readline from "node:readline/promises";
import { stdin as input, stdout as output } from "node:process";
import fs from "node:fs/promises";
import path from "node:path";

const encoder = new TextEncoder();

async function derivePbkdf2Verifier(clientDigestHex, serverSaltHex, iterations = 100_000) {
  const keyMaterial = await webcrypto.subtle.importKey(
    "raw",
    encoder.encode(clientDigestHex),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const saltBytes = Uint8Array.from(Buffer.from(serverSaltHex, "hex"));
  const bits = await webcrypto.subtle.deriveBits(
    { name: "PBKDF2", salt: saltBytes, iterations, hash: "SHA-256" },
    keyMaterial,
    256
  );
  return Buffer.from(bits).toString("hex");
}

function generateRecoveryCode() {
  const bytes = randomBytes(16);
  const hex = Buffer.from(bytes).toString("hex").toUpperCase();
  return hex.match(/.{1,4}/g).join("-");
}

function sqlEscape(val) {
  return String(val).replace(/'/g, "''");
}

async function buildAccountRecord(slotId, username, displayName, password) {
  const saltBytes = randomBytes(16);
  const passwordSaltHex = bytesToHex(saltBytes);
  const serverSaltHex = bytesToHex(randomBytes(16));

  // Compute Argon2id digest identical to Android client (`argon2kt`)
  const clientDigest = argon2id(password, saltBytes, { m: 65536, t: 3, p: 1, dkLen: 32 });
  const clientDigestHex = bytesToHex(clientDigest);
  const passwordVerifier = await derivePbkdf2Verifier(clientDigestHex, serverSaltHex);

  const recoveryCode = generateRecoveryCode();
  const recoveryDigest = argon2id(recoveryCode, saltBytes, { m: 65536, t: 3, p: 1, dkLen: 32 });
  const recoveryVerifier = await derivePbkdf2Verifier(bytesToHex(recoveryDigest), serverSaltHex);

  const userId = webcrypto.randomUUID();
  const identityId = `sig_${bytesToHex(randomBytes(8))}`;
  const now = Date.now();

  return {
    userId,
    slotId,
    username: username.trim().toLowerCase(),
    displayName: displayName.trim(),
    passwordSaltHex,
    serverSaltHex,
    passwordVerifier,
    recoveryVerifier,
    recoveryCode,
    identityId,
    now,
  };
}

async function main() {
  const rl = readline.createInterface({ input, output });
  try {
    console.log("=== System Utility — Two-Account Secure Provisioning ===");
    console.log("Notice: Passwords are hashed immediately with Argon2id (64MB) + PBKDF2-SHA256.");
    console.log("Plaintext passwords are never stored.\n");

    const u1 = process.env.SU_USER1_NAME || (await rl.question("Account 1 Username: "));
    const d1 = process.env.SU_USER1_DISPLAY || (await rl.question("Account 1 Display Name: "));
    const p1 = process.env.SU_USER1_PASS || (await rl.question("Account 1 Password (min 10 chars): "));

    const u2 = process.env.SU_USER2_NAME || (await rl.question("Account 2 Username: "));
    const d2 = process.env.SU_USER2_DISPLAY || (await rl.question("Account 2 Display Name: "));
    const p2 = process.env.SU_USER2_PASS || (await rl.question("Account 2 Password (min 10 chars): "));

    if (p1.length < 10 || p2.length < 10) {
      throw new Error("Passwords must be at least 10 characters.");
    }
    if (u1.trim().toLowerCase() === u2.trim().toLowerCase()) {
      throw new Error("Both accounts must have distinct usernames.");
    }

    const acc1 = await buildAccountRecord(1, u1, d1, p1);
    const acc2 = await buildAccountRecord(2, u2, d2, p2);

    const sql = `-- Generated one-time account provisioning SQL. Execute via wrangler d1 execute and delete immediately.
INSERT INTO users (id, slot_id, username, password_salt, server_salt, password_verifier, recovery_verifier, display_name, about, encryption_identity_id, created_at, updated_at)
VALUES
('${sqlEscape(acc1.userId)}', 1, '${sqlEscape(acc1.username)}', '${acc1.passwordSaltHex}', '${acc1.serverSaltHex}', '${acc1.passwordVerifier}', '${acc1.recoveryVerifier}', '${sqlEscape(acc1.displayName)}', 'Available', '${acc1.identityId}', ${acc1.now}, ${acc1.now}),
('${sqlEscape(acc2.userId)}', 2, '${sqlEscape(acc2.username)}', '${acc2.passwordSaltHex}', '${acc2.serverSaltHex}', '${acc2.passwordVerifier}', '${acc2.recoveryVerifier}', '${sqlEscape(acc2.displayName)}', 'Available', '${acc2.identityId}', ${acc2.now}, ${acc2.now});

INSERT OR IGNORE INTO conversations (id, user_one_id, user_two_id, created_at)
VALUES ('primary_pair', '${sqlEscape(acc1.userId)}', '${sqlEscape(acc2.userId)}', ${acc1.now});
`;

    const outPath = path.resolve(process.cwd(), ".provision-seed.sql");
    await fs.writeFile(outPath, sql, { mode: 0o600 });

    console.log("\n✅ Provisioning SQL generated at: .provision-seed.sql");
    console.log("🔑 Save these Account Recovery Codes offline safely (displayed ONCE):");
    console.log(`   - ${acc1.username}: ${acc1.recoveryCode}`);
    console.log(`   - ${acc2.username}: ${acc2.recoveryCode}`);
    console.log("\nNext step:");
    console.log("  npx wrangler d1 execute arpii --local --file=.provision-seed.sql");
    console.log("  (and `--remote` for production, then delete `.provision-seed.sql`)");
  } finally {
    rl.close();
  }
}

main().catch((err) => {
  console.error("Provisioning failed:", err.message);
  process.exit(1);
});
