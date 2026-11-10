import { generateUuid } from "../shared/crypto";

export interface UploadPreKeysInput {
  registrationId: number;
  identityKeyB64: string;
  signedPreKeyId: number;
  signedPreKeyB64: string;
  signedPreKeySignatureB64: string;
  kyberLastResortId: number;
  kyberLastResortB64: string;
  kyberLastResortSigB64: string;
  oneTimePreKeys: Array<{ keyId: number; publicKeyB64: string }>;
  kyberPreKeys: Array<{ keyId: number; publicKeyB64: string; signatureB64: string }>;
}

export interface PeerPreKeyBundle {
  userId: string;
  deviceId: number;
  registrationId: number;
  identityKeyB64: string;
  signedPreKeyId: number;
  signedPreKeyB64: string;
  signedPreKeySignatureB64: string;
  oneTimePreKey: { keyId: number; publicKeyB64: string } | null;
  kyberPreKey: { keyId: number; publicKeyB64: string; signatureB64: string };
}

interface IdentityKeyRow {
  user_id: string;
  device_id: number;
  registration_id: number;
  identity_key_b64: string;
  signed_prekey_id: number;
  signed_prekey_b64: string;
  signed_prekey_signature_b64: string;
  kyber_last_resort_id: number;
  kyber_last_resort_b64: string;
  kyber_last_resort_sig_b64: string;
}

export class KeyDirectoryRepository {
  constructor(private readonly db: D1Database) {}

  async upsertBundle(
    userId: string,
    signalDeviceId: number,
    input: UploadPreKeysInput,
    now: number
  ): Promise<{ remainingOneTimeCount: number; remainingKyberCount: number }> {
    await this.db
      .prepare(
        `INSERT INTO signal_identity_keys (
          user_id, device_id, registration_id, identity_key_b64,
          signed_prekey_id, signed_prekey_b64, signed_prekey_signature_b64,
          kyber_last_resort_id, kyber_last_resort_b64, kyber_last_resort_sig_b64, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, device_id) DO UPDATE SET
          registration_id = excluded.registration_id,
          identity_key_b64 = excluded.identity_key_b64,
          signed_prekey_id = excluded.signed_prekey_id,
          signed_prekey_b64 = excluded.signed_prekey_b64,
          signed_prekey_signature_b64 = excluded.signed_prekey_signature_b64,
          kyber_last_resort_id = excluded.kyber_last_resort_id,
          kyber_last_resort_b64 = excluded.kyber_last_resort_b64,
          kyber_last_resort_sig_b64 = excluded.kyber_last_resort_sig_b64,
          updated_at = excluded.updated_at`
      )
      .bind(
        userId,
        signalDeviceId,
        input.registrationId,
        input.identityKeyB64,
        input.signedPreKeyId,
        input.signedPreKeyB64,
        input.signedPreKeySignatureB64,
        input.kyberLastResortId,
        input.kyberLastResortB64,
        input.kyberLastResortSigB64,
        now
      )
      .run();

    for (const otk of input.oneTimePreKeys) {
      await this.db
        .prepare(
          `INSERT OR IGNORE INTO signal_one_time_prekeys (id, user_id, device_id, key_id, public_key_b64, is_used, created_at)
           VALUES (?, ?, ?, ?, ?, 0, ?)`
        )
        .bind(generateUuid(), userId, signalDeviceId, otk.keyId, otk.publicKeyB64, now)
        .run();
    }

    for (const kpk of input.kyberPreKeys) {
      await this.db
        .prepare(
          `INSERT OR IGNORE INTO signal_kyber_prekeys (id, user_id, device_id, key_id, public_key_b64, signature_b64, is_used, created_at)
           VALUES (?, ?, ?, ?, ?, ?, 0, ?)`
        )
        .bind(generateUuid(), userId, signalDeviceId, kpk.keyId, kpk.publicKeyB64, kpk.signatureB64, now)
        .run();
    }

    const otkCount = await this.db
      .prepare("SELECT COUNT(*) as total FROM signal_one_time_prekeys WHERE user_id = ? AND device_id = ? AND is_used = 0")
      .bind(userId, signalDeviceId)
      .first<{ total: number }>();
    const kpkCount = await this.db
      .prepare("SELECT COUNT(*) as total FROM signal_kyber_prekeys WHERE user_id = ? AND device_id = ? AND is_used = 0")
      .bind(userId, signalDeviceId)
      .first<{ total: number }>();

    return {
      remainingOneTimeCount: otkCount?.total ?? 0,
      remainingKyberCount: kpkCount?.total ?? 0,
    };
  }

  async popPartnerBundle(partnerUserId: string): Promise<PeerPreKeyBundle | null> {
    const idRow = await this.db
      .prepare(
        `SELECT s.*
         FROM signal_identity_keys s
         LEFT JOIN devices d ON d.user_id = s.user_id AND d.signal_device_id = s.device_id
         WHERE s.user_id = ? AND (d.revoked_at IS NULL)
         ORDER BY s.updated_at DESC
         LIMIT 1`
      )
      .bind(partnerUserId)
      .first<IdentityKeyRow>();

    if (!idRow) return null;

    // Atomically claim one unused EC one-time prekey if available
    let claimedOtk: { keyId: number; publicKeyB64: string } | null = null;
    for (let attempt = 0; attempt < 3; attempt++) {
      const candidate = await this.db
        .prepare(
          `SELECT id, key_id, public_key_b64
           FROM signal_one_time_prekeys
           WHERE user_id = ? AND device_id = ? AND is_used = 0
           ORDER BY created_at ASC, key_id ASC
           LIMIT 1`
        )
        .bind(partnerUserId, idRow.device_id)
        .first<{ id: string; key_id: number; public_key_b64: string }>();

      if (!candidate) break;

      const claim = await this.db
        .prepare("UPDATE signal_one_time_prekeys SET is_used = 1 WHERE id = ? AND is_used = 0")
        .bind(candidate.id)
        .run();

      if ((claim.meta?.changes ?? 0) > 0) {
        claimedOtk = { keyId: candidate.key_id, publicKeyB64: candidate.public_key_b64 };
        break;
      }
    }

    // Atomically claim one unused Kyber prekey, falling back to last-resort Kyber prekey
    let claimedKyber: { keyId: number; publicKeyB64: string; signatureB64: string } = {
      keyId: idRow.kyber_last_resort_id,
      publicKeyB64: idRow.kyber_last_resort_b64,
      signatureB64: idRow.kyber_last_resort_sig_b64,
    };

    for (let attempt = 0; attempt < 3; attempt++) {
      const candidate = await this.db
        .prepare(
          `SELECT id, key_id, public_key_b64, signature_b64
           FROM signal_kyber_prekeys
           WHERE user_id = ? AND device_id = ? AND is_used = 0
           ORDER BY created_at ASC, key_id ASC
           LIMIT 1`
        )
        .bind(partnerUserId, idRow.device_id)
        .first<{ id: string; key_id: number; public_key_b64: string; signature_b64: string }>();

      if (!candidate) break;

      const claim = await this.db
        .prepare("UPDATE signal_kyber_prekeys SET is_used = 1 WHERE id = ? AND is_used = 0")
        .bind(candidate.id)
        .run();

      if ((claim.meta?.changes ?? 0) > 0) {
        claimedKyber = {
          keyId: candidate.key_id,
          publicKeyB64: candidate.public_key_b64,
          signatureB64: candidate.signature_b64,
        };
        break;
      }
    }

    return {
      userId: idRow.user_id,
      deviceId: idRow.device_id,
      registrationId: idRow.registration_id,
      identityKeyB64: idRow.identity_key_b64,
      signedPreKeyId: idRow.signed_prekey_id,
      signedPreKeyB64: idRow.signed_prekey_b64,
      signedPreKeySignatureB64: idRow.signed_prekey_signature_b64,
      oneTimePreKey: claimedOtk,
      kyberPreKey: claimedKyber,
    };
  }
}
