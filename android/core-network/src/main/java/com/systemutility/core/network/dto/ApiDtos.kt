package com.systemutility.core.network.dto

import com.systemutility.core.model.UserProfile
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorEnvelope(
    val error: ApiErrorBody
)

@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val pendingDeviceId: String? = null,
    val retryAfterSeconds: Int? = null
)

@Serializable
data class SaltRequest(val username: String)

@Serializable
data class KdfSpec(
    val algorithm: String,
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int
)

@Serializable
data class SaltResponse(
    val saltHex: String,
    val kdf: KdfSpec
)

@Serializable
data class LoginRequest(
    val username: String,
    val clientDigestHex: String,
    val deviceName: String,
    val deviceId: String? = null,
    val platform: String = "android"
)

@Serializable
data class LoginResponse(
    val token: String,
    val sessionId: String,
    val expiresAt: Long,
    val deviceId: String,
    val signalDeviceId: Int,
    val selfUser: UserProfile,
    val partnerUser: UserProfile? = null
)

@Serializable
data class ChangePasswordRequest(
    val currentClientDigestHex: String,
    val newSaltHex: String,
    val newClientDigestHex: String
)

@Serializable
data class RecoverAccountRequest(
    val username: String,
    val recoveryDigestHex: String,
    val newSaltHex: String,
    val newClientDigestHex: String
)

@Serializable
data class SessionViewDto(
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val signalDeviceId: Int,
    val createdAt: Long,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val revokedAt: Long? = null
)

@Serializable
data class PendingDeviceDto(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val approvalStatus: String,
    val createdAt: Long,
    val lastActiveAt: Long
)

@Serializable
data class SessionsListResponse(
    val currentSessionId: String,
    val currentDeviceId: String = "",
    val sessions: List<SessionViewDto>,
    val pendingDevices: List<PendingDeviceDto> = emptyList()
)

@Serializable
data class ProfileResponse(
    val selfUser: UserProfile,
    val partnerUser: UserProfile? = null
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val about: String? = null,
    val avatarR2Key: String? = null,
    val avatarKeyBase64: String? = null,
    val privacyShowLastSeen: Boolean? = null,
    val privacyShowOnline: Boolean? = null
)

// Phase 2 Signal & Messaging DTOs
@Serializable
data class UploadPreKeysRequest(
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyB64: String,
    val signedPreKeySignatureB64: String,
    val kyberLastResortId: Int,
    val kyberLastResortB64: String,
    val kyberLastResortSigB64: String,
    val oneTimePreKeys: List<OneTimePreKeyDto>,
    val kyberPreKeys: List<KyberPreKeyDto>
)

@Serializable
data class OneTimePreKeyDto(val keyId: Int, val publicKeyB64: String)

@Serializable
data class KyberPreKeyDto(val keyId: Int, val publicKeyB64: String, val signatureB64: String)

@Serializable
data class PeerPreKeyBundleDto(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyB64: String,
    val signedPreKeySignatureB64: String,
    val oneTimePreKey: OneTimePreKeyDto? = null,
    val kyberPreKey: KyberPreKeyDto
)

@Serializable
data class SendMessageRequest(
    val id: String,
    val recipientUserId: String,
    val messageType: String,
    val ciphertextB64: String,
    val replyToMessageId: String? = null,
    val disappearsAt: Long? = null
)

@Serializable
data class EncryptedMessageDto(
    val id: String,
    val senderUserId: String,
    val senderDeviceId: Int,
    val recipientUserId: String,
    val messageType: String,
    val ciphertextB64: String,
    val replyToMessageId: String? = null,
    val disappearsAt: Long? = null,
    val isEdited: Boolean = false,
    val isDeletedForEveryone: Boolean = false,
    val serverSeq: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val receiptStatus: String? = null
)

@Serializable
data class SyncMessagesResponse(
    val messages: List<EncryptedMessageDto>,
    val maxSeq: Long
)

@Serializable
data class AckReceiptRequest(
    val messageIds: List<String>,
    val status: String // DELIVERED or READ
)

// Phase 3 Media DTOs
@Serializable
data class MediaUploadInitRequest(
    val encryptedSizeBytes: Long,
    val sha256Ciphertext: String,
    val mimeCategory: String
)

@Serializable
data class MediaUploadInitResponse(
    val mediaId: String,
    val r2ObjectKey: String,
    val uploadExpiresAt: Long? = null
)

@Serializable
data class MediaDownloadAuthRequest(
    val r2ObjectKey: String
)

@Serializable
data class MediaDownloadAuthResponse(
    val r2ObjectKey: String,
    val grant: String,
    val expiresAt: Long
)

// Phase 4 Cloudflare Realtime TURN & Calls DTOs
@Serializable
data class IceServerEntryDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class TurnCredentialsResponse(
    val provider: String = "cloudflare_realtime_turn",
    val ttlSeconds: Int = 3600,
    val expiresAt: Long = 0L,
    val iceServers: List<IceServerEntryDto> = emptyList()
)

@Serializable
data class CallLogRequest(
    val id: String,
    val calleeUserId: String,
    val callType: String,
    val status: String,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null
)

// Phase 6 Backup Manifest DTOs
@Serializable
data class RecordBackupRequest(
    val driveFileId: String,
    val versionTag: String,
    val sizeBytes: Long,
    val sha256Ciphertext: String
)

@Serializable
data class BackupManifestDto(
    val id: String,
    val driveFileId: String,
    val versionTag: String,
    val sizeBytes: Long,
    val sha256Ciphertext: String,
    val createdAt: Long
)

@Serializable
data class BackupManifestListResponse(
    val manifests: List<BackupManifestDto>
)
