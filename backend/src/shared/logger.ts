const REDACTED_KEYS = new Set([
  "authorization",
  "password",
  "clientdigest",
  "clientdigesthex",
  "passwordverifier",
  "recoverycode",
  "recoverydigesthex",
  "recoveryverifier",
  "token",
  "tokenhash",
  "grant",
  "ciphertext",
  "ciphertextb64",
  "identitykey",
  "prekey",
  "signature",
  "authpepper",
  "cfturnapitoken",
  "turnkeyapitoken",
  "credential",
]);

function sanitize(value: unknown): unknown {
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.map(sanitize);
  if (typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (REDACTED_KEYS.has(k.toLowerCase())) {
        out[k] = "[REDACTED]";
      } else {
        out[k] = sanitize(v);
      }
    }
    return out;
  }
  return value;
}

export const SafeLogger = {
  info(event: string, meta?: Record<string, unknown>): void {
    console.log(JSON.stringify({ level: "INFO", event, ts: Date.now(), meta: meta ? sanitize(meta) : undefined }));
  },
  warn(event: string, meta?: Record<string, unknown>): void {
    console.warn(JSON.stringify({ level: "WARN", event, ts: Date.now(), meta: meta ? sanitize(meta) : undefined }));
  },
  error(event: string, err: unknown, meta?: Record<string, unknown>): void {
    const message = err instanceof Error ? err.message : String(err);
    console.error(JSON.stringify({ level: "ERROR", event, ts: Date.now(), message, meta: meta ? sanitize(meta) : undefined }));
  },
};
