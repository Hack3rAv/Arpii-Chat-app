package com.systemutility.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.systemutility.core.database.dao.BackupDao
import com.systemutility.core.database.dao.CallDao
import com.systemutility.core.database.dao.MediaDao
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.dao.SignalStoreDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.database.entity.BackupMetaEntity
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.CallRecordingEntity
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.OutboxEntity
import com.systemutility.core.database.entity.ReactionEntity
import com.systemutility.core.database.entity.SignalKyberPreKeyEntity
import com.systemutility.core.database.entity.SignalLocalIdentityEntity
import com.systemutility.core.database.entity.SignalPeerIdentityEntity
import com.systemutility.core.database.entity.SignalPreKeyEntity
import com.systemutility.core.database.entity.SignalSessionEntity
import com.systemutility.core.database.entity.SignalSignedPreKeyEntity
import com.systemutility.core.database.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReactionEntity::class,
        AttachmentEntity::class,
        OutboxEntity::class,
        CallHistoryEntity::class,
        CallRecordingEntity::class,
        BackupMetaEntity::class,
        SignalLocalIdentityEntity::class,
        SignalPeerIdentityEntity::class,
        SignalPreKeyEntity::class,
        SignalSignedPreKeyEntity::class,
        SignalKyberPreKeyEntity::class,
        SignalSessionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SystemUtilityDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun messageDao(): MessageDao
    abstract fun mediaDao(): MediaDao
    abstract fun callDao(): CallDao
    abstract fun signalStoreDao(): SignalStoreDao
    abstract fun backupDao(): BackupDao
}
