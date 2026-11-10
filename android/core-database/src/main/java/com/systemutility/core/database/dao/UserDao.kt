package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.database.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: UserEntity)

    @Query("SELECT * FROM users WHERE isSelf = 1 LIMIT 1")
    fun observeSelfUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE isSelf = 1 LIMIT 1")
    suspend fun getSelfUser(): UserEntity?

    @Query("SELECT * FROM users WHERE isSelf = 0 LIMIT 1")
    fun observePartnerUser(): Flow<UserEntity?>

    @Query("SELECT * FROM users WHERE isSelf = 0 LIMIT 1")
    suspend fun getPartnerUser(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = 'primary_pair' LIMIT 1")
    fun observePrimaryConversation(): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = 'primary_pair' LIMIT 1")
    suspend fun getPrimaryConversation(): ConversationEntity?

    @Query("UPDATE conversations SET lastMessageId = :msgId, lastMessagePreview = :preview, lastMessageTimestamp = :timestamp WHERE id = 'primary_pair'")
    suspend fun updateConversationPreview(msgId: String, preview: String, timestamp: Long)

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE id = 'primary_pair'")
    suspend fun incrementUnread()

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = 'primary_pair'")
    suspend fun resetUnread()

    @Query("UPDATE conversations SET isMuted = :muted WHERE id = 'primary_pair'")
    suspend fun setMuted(muted: Boolean)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = 'primary_pair'")
    suspend fun setPinned(pinned: Boolean)

    @Query("DELETE FROM users")
    suspend fun clearUsers()

    @Query("SELECT * FROM users")
    suspend fun getAllUsersOnce(): List<UserEntity>
}
