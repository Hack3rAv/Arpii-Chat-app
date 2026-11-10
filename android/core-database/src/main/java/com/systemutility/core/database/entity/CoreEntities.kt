package com.systemutility.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.systemutility.core.model.UserProfile

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val slotId: Int,
    val username: String,
    val displayName: String,
    val about: String,
    val encryptionIdentityId: String,
    val avatarLocalPath: String? = null,
    val avatarR2Key: String? = null,
    val avatarKeyBase64: String? = null,
    val lastSeenAt: Long? = null,
    val privacyShowLastSeen: Boolean = true,
    val privacyShowOnline: Boolean = true,
    val isSelf: Boolean = false
) {
    fun toDomain() = UserProfile(
        id = id,
        slotId = slotId,
        username = username,
        displayName = displayName,
        about = about,
        encryptionIdentityId = encryptionIdentityId,
        avatarLocalPath = avatarLocalPath,
        avatarR2Key = avatarR2Key,
        avatarKeyBase64 = avatarKeyBase64,
        lastSeenAt = lastSeenAt,
        privacyShowLastSeen = privacyShowLastSeen,
        privacyShowOnline = privacyShowOnline
    )
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String = "primary_pair",
    val partnerUserId: String,
    val lastMessageId: String? = null,
    val lastMessagePreview: String = "",
    val lastMessageTimestamp: Long = 0L,
    val unreadCount: Int = 0,
    val isPinned: Boolean = true,
    val isMuted: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "createdAt"]),
        Index(value = ["serverSeq"]),
        Index(value = ["disappearsAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String = "primary_pair",
    val senderUserId: String,
    val recipientUserId: String,
    val kind: String, // TEXT, IMAGE, VIDEO, VOICE, AUDIO, DOCUMENT, SYSTEM
    val body: String,
    val status: String, // QUEUED, SENT, DELIVERED, READ, FAILED
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

@Entity(
    tableName = "reactions",
    primaryKeys = ["messageId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReactionEntity(
    val messageId: String,
    val userId: String,
    val emoji: String,
    val updatedAt: Long
)

@Entity(
    tableName = "attachments",
    indices = [Index(value = ["messageId"]), Index(value = ["category"])]
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val category: String, // image, video, audio, voice, document
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val r2ObjectKey: String? = null,
    val fileKeyBase64: String,
    val baseIvBase64: String,
    val sha256CiphertextHex: String,
    val localPlainPath: String? = null,
    val thumbnailPath: String? = null,
    val durationMs: Long = 0L,
    val waveformCsv: String = "",
    val transferState: String, // PENDING, UPLOADING, UPLOADED, DOWNLOADING, DOWNLOADED, FAILED
    val progressPercent: Int = 0
)

@Entity(tableName = "outbox_queue", indices = [Index(value = ["nextAttemptAt"])])
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long,
    val lastError: String? = null
)

@Entity(tableName = "call_history", indices = [Index(value = ["startedAt"])])
data class CallHistoryEntity(
    @PrimaryKey val id: String,
    val callerUserId: String,
    val calleeUserId: String,
    val callType: String, // audio, video
    val status: String, // INITIATED, RINGING, ANSWERED, REJECTED, MISSED, ENDED, FAILED
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val isLocalInitiated: Boolean
)

@Entity(tableName = "call_recordings", indices = [Index(value = ["startedAt"])])
data class CallRecordingEntity(
    @PrimaryKey val id: String,
    val callId: String,
    val recordingKind: String, // audio, video
    val encryptedFilePath: String,
    val sha256Hex: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val startedAt: Long
)

@Entity(tableName = "backup_history")
data class BackupMetaEntity(
    @PrimaryKey val id: String,
    val driveFileId: String,
    val versionTag: String,
    val sizeBytes: Long,
    val sha256CiphertextHex: String,
    val status: String, // COMPLETED, FAILED
    val errorDetail: String? = null,
    val createdAt: Long
)
