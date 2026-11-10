import { LoginAttemptRow } from "../shared/types";

const WINDOW_MS = 15 * 60 * 1000;
const LOCKOUT_MS = 15 * 60 * 1000;
const MAX_ATTEMPTS = 5;

export interface RateLimitStatus {
  blocked: boolean;
  retryAfterSeconds: number;
}

export class RateLimitRepository {
  constructor(private readonly db: D1Database) {}

  async checkKeys(keys: string[], now: number): Promise<RateLimitStatus> {
    let maxWaitMs = 0;

    for (const key of keys) {
      const row = await this.db
        .prepare("SELECT * FROM login_attempts WHERE rate_key = ? LIMIT 1")
        .bind(key)
        .first<LoginAttemptRow>();

      if (row && row.locked_until && row.locked_until > now) {
        maxWaitMs = Math.max(maxWaitMs, row.locked_until - now);
      }
    }

    return {
      blocked: maxWaitMs > 0,
      retryAfterSeconds: Math.ceil(maxWaitMs / 1000),
    };
  }

  async recordFailure(keys: string[], now: number): Promise<RateLimitStatus> {
    let maxWaitMs = 0;

    for (const key of keys) {
      const row = await this.db
        .prepare("SELECT * FROM login_attempts WHERE rate_key = ? LIMIT 1")
        .bind(key)
        .first<LoginAttemptRow>();

      if (!row || now - row.first_attempt_at > WINDOW_MS) {
        await this.db
          .prepare(
            `INSERT INTO login_attempts (rate_key, failed_count, first_attempt_at, locked_until)
             VALUES (?, 1, ?, NULL)
             ON CONFLICT(rate_key) DO UPDATE SET failed_count = 1, first_attempt_at = ?, locked_until = NULL`
          )
          .bind(key, now, now)
          .run();
      } else {
        const nextCount = row.failed_count + 1;
        const lockedUntil = nextCount >= MAX_ATTEMPTS ? now + LOCKOUT_MS : null;
        if (lockedUntil) {
          maxWaitMs = Math.max(maxWaitMs, LOCKOUT_MS);
        }

        await this.db
          .prepare("UPDATE login_attempts SET failed_count = ?, locked_until = ? WHERE rate_key = ?")
          .bind(nextCount, lockedUntil, key)
          .run();
      }
    }

    return {
      blocked: maxWaitMs > 0,
      retryAfterSeconds: Math.ceil(maxWaitMs / 1000),
    };
  }

  async clearKeys(keys: string[]): Promise<void> {
    for (const key of keys) {
      await this.db
        .prepare("DELETE FROM login_attempts WHERE rate_key = ?")
        .bind(key)
        .run();
    }
  }
}
