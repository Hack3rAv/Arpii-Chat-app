package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.systemutility.core.database.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachment(attachment: AttachmentEntity)

    @Query("SELECT * FROM attachments")
    fun observeAllAttachments(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE category IN ('image', 'video') ORDER BY id DESC")
    fun observeGalleryVisualMedia(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE category IN ('document', 'audio', 'voice') ORDER BY id DESC")
    fun observeGalleryDocumentsAndAudio(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getByMessageId(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AttachmentEntity?

    @Query("UPDATE attachments SET transferState = :state, progressPercent = :progress, r2ObjectKey = COALESCE(:r2Key, r2ObjectKey) WHERE id = :id")
    suspend fun updateTransferState(id: String, state: String, progress: Int, r2Key: String? = null)

    @Query("UPDATE attachments SET localPlainPath = :path, transferState = 'DOWNLOADED', progressPercent = 100 WHERE id = :id")
    suspend fun markDownloaded(id: String, path: String)

    @Query("UPDATE attachments SET localPlainPath = NULL, transferState = 'UPLOADED', progressPercent = 0 WHERE id = :id")
    suspend fun clearLocalFile(id: String)

    @Query("SELECT * FROM attachments")
    suspend fun dumpAllForBackup(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(attachment: AttachmentEntity)
}
