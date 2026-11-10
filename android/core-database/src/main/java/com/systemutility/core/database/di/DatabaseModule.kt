package com.systemutility.core.database.di

import android.content.Context
import androidx.room.Room
import com.systemutility.core.database.SystemUtilityDatabase
import com.systemutility.core.database.dao.BackupDao
import com.systemutility.core.database.dao.CallDao
import com.systemutility.core.database.dao.MediaDao
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.dao.SignalStoreDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.security.AndroidKeystoreManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keystoreManager: AndroidKeystoreManager
    ): SystemUtilityDatabase {
        System.loadLibrary("sqlcipher")
        val passphraseBytes = keystoreManager.getOrCreateDatabaseKey()
        val factory = SupportOpenHelperFactory(passphraseBytes)
        return Room.databaseBuilder(
            context,
            SystemUtilityDatabase::class.java,
            "system_utility_encrypted.db"
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides fun provideUserDao(db: SystemUtilityDatabase): UserDao = db.userDao()
    @Provides fun provideMessageDao(db: SystemUtilityDatabase): MessageDao = db.messageDao()
    @Provides fun provideMediaDao(db: SystemUtilityDatabase): MediaDao = db.mediaDao()
    @Provides fun provideCallDao(db: SystemUtilityDatabase): CallDao = db.callDao()
    @Provides fun provideSignalStoreDao(db: SystemUtilityDatabase): SignalStoreDao = db.signalStoreDao()
    @Provides fun provideBackupDao(db: SystemUtilityDatabase): BackupDao = db.backupDao()
}
