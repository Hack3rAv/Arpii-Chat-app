package com.systemutility.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.systemutility.core.database.entity.SignalKyberPreKeyEntity
import com.systemutility.core.database.entity.SignalLocalIdentityEntity
import com.systemutility.core.database.entity.SignalPeerIdentityEntity
import com.systemutility.core.database.entity.SignalPreKeyEntity
import com.systemutility.core.database.entity.SignalSessionEntity
import com.systemutility.core.database.entity.SignalSignedPreKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalStoreDao {
    @Query("SELECT * FROM signal_local_identity WHERE id = 1 LIMIT 1")
    suspend fun getLocalIdentity(): SignalLocalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocalIdentity(entity: SignalLocalIdentityEntity)

    @Query("SELECT * FROM signal_peer_identities WHERE addressKey = :addressKey LIMIT 1")
    suspend fun getPeerIdentity(addressKey: String): SignalPeerIdentityEntity?

    @Query("SELECT * FROM signal_peer_identities")
    fun observePeerIdentities(): Flow<List<SignalPeerIdentityEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePeerIdentity(entity: SignalPeerIdentityEntity)

    // PreKeys
    @Query("SELECT * FROM signal_prekeys WHERE preKeyId = :preKeyId LIMIT 1")
    suspend fun getPreKey(preKeyId: Int): SignalPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePreKey(entity: SignalPreKeyEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_prekeys WHERE preKeyId = :preKeyId)")
    suspend fun containsPreKey(preKeyId: Int): Boolean

    @Query("DELETE FROM signal_prekeys WHERE preKeyId = :preKeyId")
    suspend fun removePreKey(preKeyId: Int)

    @Query("SELECT COUNT(*) FROM signal_prekeys")
    suspend fun countPreKeys(): Int

    @Query("SELECT COALESCE(MAX(preKeyId), 0) FROM signal_prekeys")
    suspend fun maxPreKeyId(): Int

    @Query("SELECT * FROM signal_prekeys")
    suspend fun getAllPreKeys(): List<SignalPreKeyEntity>

    @Query("SELECT COALESCE(MAX(kyberPreKeyId), 0) FROM signal_kyber_prekeys WHERE kyberPreKeyId < 1000000")
    suspend fun maxOneTimeKyberPreKeyId(): Int

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys WHERE used = 0 AND kyberPreKeyId < 1000000")
    suspend fun countUnusedKyberPreKeys(): Int

    @Query("UPDATE signal_peer_identities SET verifiedAt = :verifiedAt WHERE addressKey = :addressKey")
    suspend fun setPeerVerified(addressKey: String, verifiedAt: Long?)

    // Signed PreKeys
    @Query("SELECT * FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId LIMIT 1")
    suspend fun getSignedPreKey(signedPreKeyId: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    suspend fun getAllSignedPreKeys(): List<SignalSignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSignedPreKey(entity: SignalSignedPreKeyEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId)")
    suspend fun containsSignedPreKey(signedPreKeyId: Int): Boolean

    @Query("DELETE FROM signal_signed_prekeys WHERE signedPreKeyId = :signedPreKeyId")
    suspend fun removeSignedPreKey(signedPreKeyId: Int)

    // Kyber PreKeys (PQXDH)
    @Query("SELECT * FROM signal_kyber_prekeys WHERE kyberPreKeyId = :kyberPreKeyId LIMIT 1")
    suspend fun getKyberPreKey(kyberPreKeyId: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys")
    suspend fun getAllKyberPreKeys(): List<SignalKyberPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveKyberPreKey(entity: SignalKyberPreKeyEntity)

    @Query("UPDATE signal_kyber_prekeys SET used = 1 WHERE kyberPreKeyId = :kyberPreKeyId")
    suspend fun markKyberPreKeyUsed(kyberPreKeyId: Int)

    // Sessions
    @Query("SELECT * FROM signal_sessions WHERE addressKey = :addressKey LIMIT 1")
    suspend fun getSession(addressKey: String): SignalSessionEntity?

    @Query("SELECT addressKey FROM signal_sessions WHERE addressKey LIKE :prefix || ':%'")
    suspend fun getSubDeviceAddresses(prefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(entity: SignalSessionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM signal_sessions WHERE addressKey = :addressKey)")
    suspend fun containsSession(addressKey: String): Boolean

    @Query("DELETE FROM signal_sessions WHERE addressKey = :addressKey")
    suspend fun deleteSession(addressKey: String)

    @Query("DELETE FROM signal_sessions WHERE addressKey LIKE :name || ':%'")
    suspend fun deleteAllSessions(name: String)
}
