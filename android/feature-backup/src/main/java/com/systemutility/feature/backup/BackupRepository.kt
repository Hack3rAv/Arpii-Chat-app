package com.systemutility.feature.backup

import android.content.Context
import com.systemutility.core.database.dao.BackupDao
import com.systemutility.core.database.dao.CallDao
import com.systemutility.core.database.dao.MediaDao
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.database.entity.BackupMetaEntity
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.ReactionEntity
import com.systemutility.core.database.entity.UserEntity
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.SecureSessionStore
import com.systemutility.core.security.StreamCipherEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup module placeholder for remote cloud sync (Google Drive deferred in this release).
 * Preserves local AES-256-GCM snapshot serialization/encryption and Recovery Key storage
 * without requesting Google Drive OAuth permissions.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
    private val messageDao: MessageDao,
    private val mediaDao: MediaDao,
    private val callDao: CallDao,
    private val backupDao: BackupDao,
    private val prefsDataStore: UserPreferencesDataStore,
    private val sessionStore: SecureSessionStore,
    private val cipherEngine: StreamCipherEngine,
    private val codec: BackupArchiveCodec,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val localVaultBackupsDir = File(context.filesDir, "local_backups").apply { mkdirs() }

    fun observeBackupHistory(): Flow<List<BackupMetaEntity>> = backupDao.observeBackups()

    fun getOrCreateRecoveryKeyHex(): String {
        val existing = sessionStore.getBackupRecoveryKey()
        if (!existing.isNullOrBlank()) return existing
        val generatedHex = BackupArchiveCodec.bytesToHex(cipherEngine.generateKeyBytes())
        sessionStore.saveBackupRecoveryKey(generatedHex)
        return generatedHex
    }

    fun setRecoveryKeyHex(inputHex: String): AppResult<String> {
        return try {
            val bytes = BackupArchiveCodec.hexToBytes(inputHex)
            val normalized = BackupArchiveCodec.bytesToHex(bytes)
            sessionStore.saveBackupRecoveryKey(normalized)
            AppResult.Success(normalized)
        } catch (t: Throwable) {
            AppResult.Error("INVALID_KEY", t.message ?: "Recovery key must be 64 hex characters.")
        }
    }

    /**
     * Exports an encrypted local snapshot archive protected with the independent Backup Recovery Key.
     * Cloud upload (Google Drive) is intentionally deferred in this release.
     */
    suspend fun createLocalEncryptedSnapshot(): AppResult<File> = withContext(ioDispatcher) {
        val recoveryHex = getOrCreateRecoveryKeyHex()
        val keyBytes = BackupArchiveCodec.hexToBytes(recoveryHex)
        val now = System.currentTimeMillis()
        val outFile = File(localVaultBackupsDir, "sysutil_local_$now.suenc")
        try {
            val payload = exportDatabasePayload(now)
            val encRes = codec.encodeAndEncrypt(payload, outFile, keyBytes)
            backupDao.insertBackupRecord(
                BackupMetaEntity(
                    id = UUID.randomUUID().toString(),
                    driveFileId = "local:${outFile.name}",
                    versionTag = "local_$now",
                    sizeBytes = encRes.encryptedBytes,
                    sha256CiphertextHex = encRes.sha256CiphertextHex,
                    status = "LOCAL_SNAPSHOT",
                    errorDetail = null,
                    createdAt = now
                )
            )
            AppResult.Success(outFile)
        } catch (t: Throwable) {
            outFile.delete()
            AppResult.Error("LOCAL_BACKUP_FAILED", t.message ?: "Failed saving encrypted snapshot.")
        }
    }

    suspend fun restoreLocalEncryptedSnapshot(
        fileName: String,
        expectedSha256Hex: String? = null
    ): AppResult<Int> = withContext(ioDispatcher) {
        val keyHex = sessionStore.getBackupRecoveryKey()
            ?: return@withContext AppResult.Error("MISSING_KEY", "Enter or generate a Backup Recovery Key first.")
        val keyBytes = runCatching { BackupArchiveCodec.hexToBytes(keyHex) }.getOrElse {
            return@withContext AppResult.Error("INVALID_KEY", it.message ?: "Invalid recovery key.")
        }
        val cleanName = fileName.removePrefix("local:")
        val target = File(localVaultBackupsDir, cleanName)
        if (!target.exists()) {
            return@withContext AppResult.Error("NOT_FOUND", "Local snapshot file no longer exists on this device.")
        }
        try {
            val payload = codec.verifyAndDecrypt(
                encryptedFile = target,
                recoveryKeyBytes = keyBytes,
                expectedSha256Hex = expectedSha256Hex
            )
            applyRestoredPayload(payload)
            AppResult.Success(payload.messages.size)
        } catch (sec: SecurityException) {
            AppResult.Error("TAMPER_OR_CORRUPT", sec.message ?: "Backup verification failed.")
        } catch (t: Throwable) {
            AppResult.Error("RESTORE_FAILED", t.message ?: "Wrong recovery key or damaged backup archive.")
        }
    }

    private suspend fun exportDatabasePayload(now: Long): BackupArchivePayload {
        val users = userDao.getAllUsersOnce().map { u ->
            BackupUserRecord(
                id = u.id,
                slotId = u.slotId,
                username = u.username,
                displayName = u.displayName,
                about = u.about,
                encryptionIdentityId = u.encryptionIdentityId,
                avatarR2Key = u.avatarR2Key,
                avatarKeyBase64 = u.avatarKeyBase64,
                lastSeenAt = u.lastSeenAt,
                privacyShowLastSeen = u.privacyShowLastSeen,
                privacyShowOnline = u.privacyShowOnline,
                isSelf = u.isSelf
            )
        }
        val messages = messageDao.dumpAllMessagesForBackup().map { m ->
            BackupMessageRecord(
                id = m.id,
                conversationId = m.conversationId,
                senderUserId = m.senderUserId,
                recipientUserId = m.recipientUserId,
                kind = m.kind,
                body = m.body,
                status = m.status,
                replyToMessageId = m.replyToMessageId,
                replyPreviewText = m.replyPreviewText,
                isForwarded = m.isForwarded,
                isEdited = m.isEdited,
                isDeleted = m.isDeleted,
                isPinned = m.isPinned,
                disappearsAt = m.disappearsAt,
                serverSeq = m.serverSeq,
                createdAt = m.createdAt,
                updatedAt = m.updatedAt
            )
        }
        val reactions = messageDao.dumpAllReactionsForBackup().map { r ->
            BackupReactionRecord(r.messageId, r.userId, r.emoji, r.updatedAt)
        }
        val attachments = mediaDao.dumpAllForBackup().map { a ->
            BackupAttachmentRecord(
                id = a.id,
                messageId = a.messageId,
                category = a.category,
                fileName = a.fileName,
                mimeType = a.mimeType,
                sizeBytes = a.sizeBytes,
                r2ObjectKey = a.r2ObjectKey,
                fileKeyBase64 = a.fileKeyBase64,
                baseIvBase64 = a.baseIvBase64,
                sha256CiphertextHex = a.sha256CiphertextHex,
                durationMs = a.durationMs,
                waveformCsv = a.waveformCsv,
                transferState = a.transferState
            )
        }
        val calls = callDao.getAllCallsOnce().map { c ->
            BackupCallRecord(
                id = c.id,
                callerUserId = c.callerUserId,
                calleeUserId = c.calleeUserId,
                callType = c.callType,
                status = c.status,
                startedAt = c.startedAt,
                answeredAt = c.answeredAt,
                endedAt = c.endedAt,
                isLocalInitiated = c.isLocalInitiated
            )
        }
        val prefs = prefsDataStore.exportAllForBackup()

        return BackupArchivePayload(
            version = 1,
            createdAt = now,
            users = users,
            messages = messages,
            reactions = reactions,
            attachments = attachments,
            calls = calls,
            preferences = prefs
        )
    }

    private suspend fun applyRestoredPayload(payload: BackupArchivePayload) {
        val currentSelfId = sessionStore.getSession()?.userId
        for (u in payload.users) {
            val existing = userDao.getAllUsersOnce().firstOrNull { it.id == u.id }
            if (existing == null) {
                val resolvedIsSelf = if (currentSelfId != null) u.id == currentSelfId else u.isSelf
                userDao.upsertUser(
                    UserEntity(
                        id = u.id,
                        slotId = u.slotId,
                        username = u.username,
                        displayName = u.displayName,
                        about = u.about,
                        encryptionIdentityId = u.encryptionIdentityId,
                        avatarR2Key = u.avatarR2Key,
                        avatarKeyBase64 = u.avatarKeyBase64,
                        lastSeenAt = u.lastSeenAt,
                        privacyShowLastSeen = u.privacyShowLastSeen,
                        privacyShowOnline = u.privacyShowOnline,
                        isSelf = resolvedIsSelf
                    )
                )
                if (!resolvedIsSelf) {
                    userDao.upsertConversation(ConversationEntity(id = "primary_pair", partnerUserId = u.id))
                }
            }
        }

        messageDao.insertBatchForRestore(
            payload.messages.map { m ->
                MessageEntity(
                    id = m.id,
                    conversationId = m.conversationId,
                    senderUserId = m.senderUserId,
                    recipientUserId = m.recipientUserId,
                    kind = m.kind,
                    body = m.body,
                    status = m.status,
                    replyToMessageId = m.replyToMessageId,
                    replyPreviewText = m.replyPreviewText,
                    isForwarded = m.isForwarded,
                    isEdited = m.isEdited,
                    isDeleted = m.isDeleted,
                    isPinned = m.isPinned,
                    disappearsAt = m.disappearsAt,
                    serverSeq = m.serverSeq,
                    createdAt = m.createdAt,
                    updatedAt = m.updatedAt
                )
            }
        )

        payload.reactions.forEach { r ->
            messageDao.insertReactionIgnore(ReactionEntity(r.messageId, r.userId, r.emoji, r.updatedAt))
        }

        payload.attachments.forEach { a ->
            mediaDao.insertIgnore(
                AttachmentEntity(
                    id = a.id,
                    messageId = a.messageId,
                    category = a.category,
                    fileName = a.fileName,
                    mimeType = a.mimeType,
                    sizeBytes = a.sizeBytes,
                    r2ObjectKey = a.r2ObjectKey,
                    fileKeyBase64 = a.fileKeyBase64,
                    baseIvBase64 = a.baseIvBase64,
                    sha256CiphertextHex = a.sha256CiphertextHex,
                    localPlainPath = null,
                    thumbnailPath = null,
                    durationMs = a.durationMs,
                    waveformCsv = a.waveformCsv,
                    transferState = if (a.r2ObjectKey != null) "PENDING" else a.transferState,
                    progressPercent = 0
                )
            )
        }

        payload.calls.forEach { c ->
            callDao.insertCallIgnore(
                CallHistoryEntity(
                    id = c.id,
                    callerUserId = c.callerUserId,
                    calleeUserId = c.calleeUserId,
                    callType = c.callType,
                    status = c.status,
                    startedAt = c.startedAt,
                    answeredAt = c.answeredAt,
                    endedAt = c.endedAt,
                    isLocalInitiated = c.isLocalInitiated
                )
            )
        }

        if (payload.preferences.isNotEmpty()) {
            prefsDataStore.restoreFromBackup(payload.preferences)
        }
    }
}
