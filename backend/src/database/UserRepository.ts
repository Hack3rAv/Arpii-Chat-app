import { UserRow } from "../shared/types";

export class UserRepository {
  constructor(private readonly db: D1Database) {}

  async findByUsername(username: string): Promise<UserRow | null> {
    const normalized = username.trim().toLowerCase();
    const row = await this.db
      .prepare("SELECT * FROM users WHERE LOWER(username) = ? LIMIT 1")
      .bind(normalized)
      .first<UserRow>();
    return row ?? null;
  }

  async findById(userId: string): Promise<UserRow | null> {
    const row = await this.db
      .prepare("SELECT * FROM users WHERE id = ? LIMIT 1")
      .bind(userId)
      .first<UserRow>();
    return row ?? null;
  }

  async findPartner(myUserId: string): Promise<UserRow | null> {
    const row = await this.db
      .prepare("SELECT * FROM users WHERE id != ? ORDER BY slot_id ASC LIMIT 1")
      .bind(myUserId)
      .first<UserRow>();
    return row ?? null;
  }

  async countUsers(): Promise<number> {
    const res = await this.db
      .prepare("SELECT COUNT(*) as total FROM users")
      .first<{ total: number }>();
    return res?.total ?? 0;
  }

  async updatePasswordVerifier(
    userId: string,
    newPasswordSalt: string,
    newServerSalt: string,
    newPasswordVerifier: string,
    now: number
  ): Promise<void> {
    await this.db
      .prepare(
        `UPDATE users
         SET password_salt = ?, server_salt = ?, password_verifier = ?, updated_at = ?
         WHERE id = ?`
      )
      .bind(newPasswordSalt, newServerSalt, newPasswordVerifier, now, userId)
      .run();
  }

  async updateProfile(
    userId: string,
    fields: {
      displayName?: string;
      about?: string;
      avatarR2Key?: string | null;
      avatarKeyBase64?: string | null;
      privacyShowLastSeen?: number;
      privacyShowOnline?: number;
    },
    now: number
  ): Promise<void> {
    const current = await this.findById(userId);
    if (!current) return;

    await this.db
      .prepare(
        `UPDATE users
         SET display_name = ?, about = ?, avatar_r2_key = ?, avatar_key_nonce = ?,
             privacy_show_last_seen = ?, privacy_show_online = ?, updated_at = ?
         WHERE id = ?`
      )
      .bind(
        fields.displayName ?? current.display_name,
        fields.about ?? current.about,
        fields.avatarR2Key === undefined ? current.avatar_r2_key : fields.avatarR2Key,
        fields.avatarKeyBase64 === undefined ? current.avatar_key_nonce : fields.avatarKeyBase64,
        fields.privacyShowLastSeen ?? current.privacy_show_last_seen,
        fields.privacyShowOnline ?? current.privacy_show_online,
        now,
        userId
      )
      .run();
  }

  async touchLastSeen(userId: string, now: number): Promise<void> {
    await this.db
      .prepare("UPDATE users SET last_seen_at = ? WHERE id = ?")
      .bind(now, userId)
      .run();
  }
}
