package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.OutboxEntity
import com.systemutility.core.database.entity.ReactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = 'primary_pair' ORDER BY createdAt ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = 'primary_pair' AND body LIKE '%' || :query || '%' AND isDeleted = 0 ORDER BY createdAt DESC")
    fun searchMessages(query: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isPinned = 1 AND isDeleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestPinnedMessage(): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = 'primary_pair' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestMessage(): MessageEntity?

    @Query("SELECT COALESCE(MAX(serverSeq), 0) FROM messages")
    suspend fun getMaxServerSeq(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status, serverSeq = :serverSeq, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun markSent(messageId: String, status: String, serverSeq: Long, updatedAt: Long)

    @Query("UPDATE messages SET status = :status, updatedAt = :updatedAt WHERE id = :messageId AND status != 'READ'")
    suspend fun updateDeliveryStatus(messageId: String, status: String, updatedAt: Long)

    @Query("UPDATE messages SET status = 'READ', updatedAt = :updatedAt WHERE senderUserId = :selfUserId AND status IN ('SENT', 'DELIVERED') AND createdAt <= :upToTimestamp")
    suspend fun markAllSentReadUpTo(selfUserId: String, upToTimestamp: Long, updatedAt: Long)

    @Query("UPDATE messages SET body = :newBody, isEdited = 1, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun editMessageBody(messageId: String, newBody: String, updatedAt: Long)

    @Query("UPDATE messages SET body = 'Message deleted', isDeleted = 1, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun markDeleted(messageId: String, updatedAt: Long)

    @Query("UPDATE messages SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun setPinned(messageId: String, pinned: Boolean, updatedAt: Long)

    @Query("DELETE FROM messages WHERE disappearsAt IS NOT NULL AND disappearsAt <= :now")
    suspend fun purgeExpiredMessages(now: Long): Int

    // Reactions
    @Query("SELECT * FROM reactions")
    fun observeAllReactions(): Flow<List<ReactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND userId = :userId")
    suspend fun removeReaction(messageId: String, userId: String)

    // Offline Outbox
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueueOutbox(item: OutboxEntity)

    @Query("SELECT o.* FROM outbox_queue o WHERE o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt ASC")
    suspend fun getDueOutboxItems(now: Long): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox_queue")
    fun observePendingOutboxCount(): Flow<Int>

    @Query("DELETE FROM outbox_queue WHERE messageId = :messageId")
    suspend fun removeOutbox(messageId: String)

    @Query("UPDATE outbox_queue SET attemptCount = attemptCount + 1, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE messageId = :messageId")
    suspend fun scheduleOutboxRetry(messageId: String, nextAttemptAt: Long, error: String?)

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun dumpAllMessagesForBackup(): List<MessageEntity>

    @Query("SELECT * FROM reactions")
    suspend fun dumpAllReactionsForBackup(): List<ReactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReactionIgnore(reaction: ReactionEntity)

    @Transaction
    suspend fun insertBatchForRestore(messages: List<MessageEntity>) {
        messages.forEach { insertIgnore(it) }
    }
}
