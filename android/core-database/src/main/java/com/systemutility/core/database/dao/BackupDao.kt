package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.systemutility.core.database.entity.BackupMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackupRecord(record: BackupMetaEntity)

    @Query("SELECT * FROM backup_history ORDER BY createdAt DESC")
    fun observeBackups(): Flow<List<BackupMetaEntity>>

    @Query("SELECT * FROM backup_history WHERE status = 'COMPLETED' ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestSuccessfulBackup(): Flow<BackupMetaEntity?>
}
