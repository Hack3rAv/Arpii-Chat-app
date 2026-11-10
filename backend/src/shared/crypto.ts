const encoder = new TextEncoder();

export const SERVER_PBKDF2_ITERATIONS = 100_000;

export function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

export function fromHex(hex: string): Uint8Array {
  if (hex.length % 2 !== 0) {
    throw new Error("Invalid hex length");
  }
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

export function randomHex(byteLength = 32): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  return toHex(bytes);
}

export function generateUuid(): string {
  return crypto.randomUUID();
}

export async function sha256Hex(input: string | Uint8Array): Promise<string> {
  const data = typeof input === "string" ? encoder.encode(input) : input;
  const buf = await crypto.subtle.digest("SHA-256", data);
  return toHex(new Uint8Array(buf));
}

export async function hmacSha256Hex(secret: string, message: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = await crypto.subtle.sign("HMAC", key, encoder.encode(message));
  return toHex(new Uint8Array(sig));
}

/**
 * Derives a deterministic 16-byte fake salt for nonexistent usernames so attackers
 * cannot enumerate valid accounts via `/api/v1/auth/salt`.
 */
export async function derivePseudoSalt(pepper: string, username: string): Promise<string> {
  const fullHex = await hmacSha256Hex(pepper, `salt:${username.trim().toLowerCase()}`);
  return fullHex.slice(0, 32); // 16 bytes = 32 hex chars
}

/**
 * Server-side verifier strengthening over the client-computed Argon2id digest.
 * Uses WebCrypto native C++ PBKDF2-HMAC-SHA256 at Cloudflare's 100,000 iteration limit.
 */
export async function deriveServerVerifier(
  clientDigestHex: string,
  serverSaltHex: string,
  iterations = SERVER_PBKDF2_ITERATIONS
): Promise<string> {
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    encoder.encode(clientDigestHex),
    { name: "PBKDF2" },
    false,
    ["deriveBits"]
  );
  const bits = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt: fromHex(serverSaltHex),
      iterations,
      hash: "SHA-256",
    },
    keyMaterial,
    256
  );
  return toHex(new Uint8Array(bits));
}

/**
 * Constant-time equality comparison for fixed-length hex verifiers.
 */
export function constantTimeEqualHex(aHex: string, bHex: string): boolean {
  if (aHex.length !== bHex.length) {
    return false;
  }
  const a = encoder.encode(aHex);
  const b = encoder.encode(bHex);
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= (a[i] ?? 0) ^ (b[i] ?? 0);
  }
  return diff === 0;
}
