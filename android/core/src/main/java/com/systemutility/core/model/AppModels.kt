package com.systemutility.core.model

import kotlinx.serialization.Serializable

object FeatureFlags {
    const val CALLS_ENABLED = false
    const val BACKUP_ENABLED = false
}

@Serializable
data class UserProfile(
    val id: String,
    val slotId: Int,
    val username: String,
    val displayName: String,
    val about: String = "",
    val encryptionIdentityId: String = "",
    val avatarLocalPath: String? = null,
    val avatarR2Key: String? = null,
    val avatarKeyBase64: String? = null,
    val lastSeenAt: Long? = null,
    val privacyShowLastSeen: Boolean = true,
    val privacyShowOnline: Boolean = true
)

@Serializable
data class SessionCredentials(
    val userId: String,
    val slotId: Int,
    val username: String,
    val token: String,
    val sessionId: String,
    val deviceId: String,
    val signalDeviceId: Int,
    val expiresAt: Long,
    val baseUrl: String
)

enum class MessageDeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class MessageKind {
    TEXT,
    IMAGE,
    VIDEO,
    VOICE,
    AUDIO,
    DOCUMENT,
    SYSTEM
}

enum class MediaCategory(val apiValue: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    VOICE("voice"),
    DOCUMENT("document"),
    AVATAR("avatar")
}

enum class CallType(val apiValue: String) {
    AUDIO("audio"),
    VIDEO("video")
}

enum class CallStatus {
    INITIATED,
    RINGING,
    ANSWERED,
    REJECTED,
    MISSED,
    ENDED,
    FAILED
}

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class DisappearingTimer(val seconds: Long, val label: String) {
    OFF(0L, "Off"),
    MINUTES_5(300L, "5 minutes"),
    HOUR_1(3600L, "1 hour"),
    HOURS_24(86400L, "24 hours"),
    DAYS_7(604800L, "7 days")
}

@Serializable
data class ChatAppearanceConfig(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentHex: Long = 0xFF0F766EL,
    val outgoingBubbleHex: Long = 0xFF0F766EL,
    val incomingBubbleHex: Long = 0xFF1F2937L,
    val bubbleCornerRadiusDp: Int = 18,
    val fontScale: Float = 1.0f,
    val wallpaperId: String = "minimal_slate",
    val customWallpaperUri: String? = null
)

@Serializable
data class PrivacyPreferences(
    val sendReadReceipts: Boolean = true,
    val sendTypingIndicators: Boolean = true,
    val showOnlineStatus: Boolean = true,
    val showLastSeen: Boolean = true,
    val disappearingTimerSeconds: Long = 0L,
    val appLockEnabled: Boolean = false,
    val biometricLockEnabled: Boolean = false,
    val screenshotProtectionEnabled: Boolean = false
)

@Serializable
data class NotificationPreferences(
    val messageSoundEnabled: Boolean = true,
    val callRingtoneEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val showNotificationPreviews: Boolean = false,
    val stealthNotificationText: Boolean = true,
    val muteConversation: Boolean = false,
    val dndEnabled: Boolean = false
)

@Serializable
data class EncryptedAttachmentMeta(
    val attachmentId: String,
    val category: String, // image, video, audio, voice, document
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val r2ObjectKey: String,
    val fileKeyBase64: String,
    val baseIvBase64: String,
    val sha256CiphertextHex: String,
    val durationMs: Long = 0L,
    val waveformCsv: String = ""
)

@Serializable
data class EncryptedEnvelopePayload(
    val v: Int = 1,
    val action: String = "MESSAGE", // MESSAGE, EDIT, REACTION
    val messageId: String,
    val kind: String = "TEXT", // TEXT, IMAGE, VIDEO, VOICE, AUDIO, DOCUMENT
    val body: String = "",
    val isForwarded: Boolean = false,
    val targetMessageId: String? = null,
    val emoji: String? = null,
    val expiresInSeconds: Long? = null,
    val attachment: EncryptedAttachmentMeta? = null
)
