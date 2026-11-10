package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.CallRecordingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCall(call: CallHistoryEntity)

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC")
    fun observeCallHistory(): Flow<List<CallHistoryEntity>>

    @Query("UPDATE call_history SET status = :status, answeredAt = COALESCE(:answeredAt, answeredAt), endedAt = COALESCE(:endedAt, endedAt) WHERE id = :callId")
    suspend fun updateCallState(callId: String, status: String, answeredAt: Long? = null, endedAt: Long? = null)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: CallRecordingEntity)

    @Query("SELECT * FROM call_recordings ORDER BY startedAt DESC")
    fun observeRecordings(): Flow<List<CallRecordingEntity>>

    @Query("DELETE FROM call_recordings WHERE id = :recordingId")
    suspend fun deleteRecording(recordingId: String)

    @Query("SELECT * FROM call_history ORDER BY startedAt DESC")
    suspend fun getAllCallsOnce(): List<CallHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCallIgnore(call: CallHistoryEntity)
}
