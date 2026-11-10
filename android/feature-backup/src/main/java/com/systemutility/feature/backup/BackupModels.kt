package com.systemutility.feature.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupUserRecord(
    val id: String,
    val slotId: Int,
    val username: String,
    val displayName: String,
    val about: String,
    val encryptionIdentityId: String,
    val avatarR2Key: String? = null,
    val avatarKeyBase64: String? = null,
    val lastSeenAt: Long? = null,
    val privacyShowLastSeen: Boolean = true,
    val privacyShowOnline: Boolean = true,
    val isSelf: Boolean = false
)

@Serializable
data class BackupMessageRecord(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val recipientUserId: String,
    val kind: String,
    val body: String,
    val status: String,
    val replyToMessageId: String? = null,
    val replyPreviewText: String? = null,
    val isForwarded: Boolean = false,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val isPinned: Boolean = false,
    val disappearsAt: Long? = null,
    val serverSeq: Long = 0L,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupReactionRecord(
    val messageId: String,
    val userId: String,
    val emoji: String,
    val updatedAt: Long
)

@Serializable
data class BackupAttachmentRecord(
    val id: String,
    val messageId: String,
    val category: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val r2ObjectKey: String? = null,
    val fileKeyBase64: String,
    val baseIvBase64: String,
    val sha256CiphertextHex: String,
    val durationMs: Long = 0L,
    val waveformCsv: String = "",
    val transferState: String
)

@Serializable
data class BackupCallRecord(
    val id: String,
    val callerUserId: String,
    val calleeUserId: String,
    val callType: String,
    val status: String,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val isLocalInitiated: Boolean
)

@Serializable
data class BackupArchivePayload(
    val version: Int = 1,
    val createdAt: Long,
    val users: List<BackupUserRecord> = emptyList(),
    val messages: List<BackupMessageRecord> = emptyList(),
    val reactions: List<BackupReactionRecord> = emptyList(),
    val attachments: List<BackupAttachmentRecord> = emptyList(),
    val calls: List<BackupCallRecord> = emptyList(),
    val preferences: Map<String, String> = emptyMap()
)

data class DriveBackupFileEntry(
    val driveFileId: String,
    val name: String,
    val sizeBytes: Long,
    val createdTimeMs: Long,
    val expectedSha256Hex: String? = null
)
