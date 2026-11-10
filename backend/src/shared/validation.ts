import { z } from "zod";

export const SaltRequestSchema = z.object({
  username: z.string().trim().min(3).max(48).regex(/^[a-zA-Z0-9_.-]+$/),
});

export const LoginRequestSchema = z.object({
  username: z.string().trim().min(3).max(48).regex(/^[a-zA-Z0-9_.-]+$/),
  clientDigestHex: z.string().regex(/^[a-f0-9]{64}$/),
  deviceName: z.string().trim().min(1).max(64),
  deviceId: z.string().uuid().optional(),
  platform: z.enum(["android", "ios", "cli"]).default("android"),
});

export const ChangePasswordRequestSchema = z.object({
  currentClientDigestHex: z.string().regex(/^[a-f0-9]{64}$/),
  newSaltHex: z.string().regex(/^[a-f0-9]{32}$/),
  newClientDigestHex: z.string().regex(/^[a-f0-9]{64}$/),
});

export const RecoverAccountRequestSchema = z.object({
  username: z.string().trim().min(3).max(48).regex(/^[a-zA-Z0-9_.-]+$/),
  recoveryDigestHex: z.string().regex(/^[a-f0-9]{64}$/),
  newSaltHex: z.string().regex(/^[a-f0-9]{32}$/),
  newClientDigestHex: z.string().regex(/^[a-f0-9]{64}$/),
});

export const UpdateProfileSchema = z.object({
  displayName: z.string().trim().min(1).max(64).optional(),
  about: z.string().trim().max(180).optional(),
  avatarR2Key: z.string().trim().max(256).nullish(),
  avatarKeyBase64: z.string().trim().max(128).nullish(),
  privacyShowLastSeen: z.boolean().optional(),
  privacyShowOnline: z.boolean().optional(),
});

export const BackupManifestSchema = z.object({
  driveFileId: z.string().trim().min(1).max(256),
  versionTag: z.string().trim().min(1).max(64),
  sizeBytes: z.number().int().nonnegative(),
  sha256Ciphertext: z.string().regex(/^[a-f0-9]{64}$/),
});

export const OneTimePreKeySchema = z.object({
  keyId: z.number().int().nonnegative(),
  publicKeyB64: z.string().min(16).max(512),
});

export const KyberPreKeySchema = z.object({
  keyId: z.number().int().nonnegative(),
  publicKeyB64: z.string().min(64).max(4096),
  signatureB64: z.string().min(32).max(512),
});

export const UploadPreKeysSchema = z.object({
  registrationId: z.number().int().nonnegative(),
  identityKeyB64: z.string().min(16).max(512),
  signedPreKeyId: z.number().int().nonnegative(),
  signedPreKeyB64: z.string().min(16).max(512),
  signedPreKeySignatureB64: z.string().min(32).max(512),
  kyberLastResortId: z.number().int().nonnegative(),
  kyberLastResortB64: z.string().min(64).max(4096),
  kyberLastResortSigB64: z.string().min(32).max(512),
  oneTimePreKeys: z.array(OneTimePreKeySchema).max(200),
  kyberPreKeys: z.array(KyberPreKeySchema).max(100),
});

export const SendMessageSchema = z.object({
  id: z.string().uuid(),
  recipientUserId: z.string().min(1).max(64),
  messageType: z.enum(["signal_whisper", "signal_prekey", "edit_envelope", "reaction_envelope", "receipt_envelope", "media_envelope"]),
  ciphertextB64: z.string().min(4).max(262144),
  replyToMessageId: z.string().uuid().nullish(),
  disappearsAt: z.number().int().positive().nullish(),
});

export const AckReceiptSchema = z.object({
  messageIds: z.array(z.string().uuid()).min(1).max(200),
  status: z.enum(["DELIVERED", "READ"]),
});

export const EditMessageSchema = z.object({
  id: z.string().uuid().optional(),
  recipientUserId: z.string().min(1).max(64),
  messageType: z.enum(["signal_whisper", "signal_prekey", "edit_envelope"]).default("signal_whisper"),
  ciphertextB64: z.string().min(4).max(262144),
  replyToMessageId: z.string().uuid().nullish(),
  disappearsAt: z.number().int().positive().nullish(),
});

export const MediaUploadInitSchema = z.object({
  encryptedSizeBytes: z.number().int().positive(),
  sha256Ciphertext: z.string().regex(/^[a-f0-9]{64}$/),
  mimeCategory: z.enum(["image", "video", "audio", "voice", "document", "avatar"]),
});

export const MediaDownloadAuthSchema = z.object({
  r2ObjectKey: z.string().trim().min(5).max(256),
});

export const CallLogSchema = z.object({
  id: z.string().uuid(),
  calleeUserId: z.string().min(1).max(64),
  callType: z.enum(["audio", "video"]),
  status: z.enum(["INITIATED", "RINGING", "ANSWERED", "REJECTED", "MISSED", "ENDED", "FAILED"]),
  startedAt: z.number().int().positive(),
  answeredAt: z.number().int().positive().nullish(),
  endedAt: z.number().int().positive().nullish(),
});
