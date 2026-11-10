package com.systemutility.core.database.signal

import com.systemutility.core.database.dao.SignalStoreDao
import com.systemutility.core.database.entity.SignalKyberPreKeyEntity
import com.systemutility.core.database.entity.SignalLocalIdentityEntity
import com.systemutility.core.database.entity.SignalPeerIdentityEntity
import com.systemutility.core.database.entity.SignalPreKeyEntity
import com.systemutility.core.database.entity.SignalSessionEntity
import com.systemutility.core.database.entity.SignalSignedPreKeyEntity
import com.systemutility.core.model.EncryptedEnvelopePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private class RestartableSignalStoreDao : SignalStoreDao {
    private var localIdentity: SignalLocalIdentityEntity? = null
    private val peerIdentities = mutableMapOf<String, SignalPeerIdentityEntity>()
    private val peerFlow = MutableStateFlow<List<SignalPeerIdentityEntity>>(emptyList())
    private val preKeys = mutableMapOf<Int, SignalPreKeyEntity>()
    private val signedPreKeys = mutableMapOf<Int, SignalSignedPreKeyEntity>()
    private val kyberPreKeys = mutableMapOf<Int, SignalKyberPreKeyEntity>()
    private val sessions = mutableMapOf<String, SignalSessionEntity>()

    override suspend fun getLocalIdentity(): SignalLocalIdentityEntity? = localIdentity
    override suspend fun saveLocalIdentity(entity: SignalLocalIdentityEntity) {
        localIdentity = entity
    }

    override suspend fun getPeerIdentity(addressKey: String): SignalPeerIdentityEntity? = peerIdentities[addressKey]
    override fun observePeerIdentities(): Flow<List<SignalPeerIdentityEntity>> = peerFlow
    override suspend fun savePeerIdentity(entity: SignalPeerIdentityEntity) {
        peerIdentities[entity.addressKey] = entity
        peerFlow.value = peerIdentities.values.toList()
    }

    override suspend fun getPreKey(preKeyId: Int): SignalPreKeyEntity? = preKeys[preKeyId]
    override suspend fun savePreKey(entity: SignalPreKeyEntity) {
        preKeys[entity.preKeyId] = entity
    }

    override suspend fun containsPreKey(preKeyId: Int): Boolean = preKeys.containsKey(preKeyId)
    override suspend fun removePreKey(preKeyId: Int) {
        preKeys.remove(preKeyId)
    }

    override suspend fun countPreKeys(): Int = preKeys.size
    override suspend fun maxPreKeyId(): Int = preKeys.keys.maxOrNull() ?: 0
    override suspend fun getAllPreKeys(): List<SignalPreKeyEntity> = preKeys.values.toList()

    override suspend fun getSignedPreKey(signedPreKeyId: Int): SignalSignedPreKeyEntity? = signedPreKeys[signedPreKeyId]
    override suspend fun getAllSignedPreKeys(): List<SignalSignedPreKeyEntity> = signedPreKeys.values.toList()
    override suspend fun saveSignedPreKey(entity: SignalSignedPreKeyEntity) {
        signedPreKeys[entity.signedPreKeyId] = entity
    }

    override suspend fun containsSignedPreKey(signedPreKeyId: Int): Boolean = signedPreKeys.containsKey(signedPreKeyId)
    override suspend fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
    }

    override suspend fun getKyberPreKey(kyberPreKeyId: Int): SignalKyberPreKeyEntity? = kyberPreKeys[kyberPreKeyId]
    override suspend fun getAllKyberPreKeys(): List<SignalKyberPreKeyEntity> = kyberPreKeys.values.toList()
    override suspend fun saveKyberPreKey(entity: SignalKyberPreKeyEntity) {
        kyberPreKeys[entity.kyberPreKeyId] = entity
    }

    override suspend fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        kyberPreKeys[kyberPreKeyId]?.let { kyberPreKeys[kyberPreKeyId] = it.copy(used = true) }
    }

    override suspend fun maxOneTimeKyberPreKeyId(): Int =
        kyberPreKeys.keys.filter { it < SignalCryptoEngine.LAST_RESORT_KYBER_ID }.maxOrNull() ?: 0

    override suspend fun countUnusedKyberPreKeys(): Int =
        kyberPreKeys.values.count { !it.used && it.kyberPreKeyId < SignalCryptoEngine.LAST_RESORT_KYBER_ID }

    override suspend fun setPeerVerified(addressKey: String, verifiedAt: Long?) {
        peerIdentities[addressKey]?.let {
            peerIdentities[addressKey] = it.copy(verifiedAt = verifiedAt)
            peerFlow.value = peerIdentities.values.toList()
        }
    }

    override suspend fun getSession(addressKey: String): SignalSessionEntity? = sessions[addressKey]
    override suspend fun getSubDeviceAddresses(prefix: String): List<String> =
        sessions.keys.filter { it.startsWith("$prefix:") }

    override suspend fun saveSession(entity: SignalSessionEntity) {
        sessions[entity.addressKey] = entity
    }

    override suspend fun containsSession(addressKey: String): Boolean = sessions.containsKey(addressKey)
    override suspend fun deleteSession(addressKey: String) {
        sessions.remove(addressKey)
    }

    override suspend fun deleteAllSessions(name: String) {
        sessions.keys.removeAll { it.startsWith("$name:") }
    }
}

class SignalPersistenceAcrossRestartTest {

    @Test
    fun `identity and double ratchet session persist cleanly when engine recreates after app update`() = runBlocking {
        val aliceDao = RestartableSignalStoreDao()
        val bobDao = RestartableSignalStoreDao()

        val aliceV1 = SignalCryptoEngine(PersistentSignalProtocolStore(aliceDao), aliceDao)
        val bobV1 = SignalCryptoEngine(PersistentSignalProtocolStore(bobDao), bobDao)

        val bobBundle = bobV1.prepareLocalBundle()
        val aliceIdentityBefore = aliceV1.prepareLocalBundle().identityKeyB64

        aliceV1.processPartnerBundle(
            RemoteBundleInput(
                userId = "user-2",
                deviceId = 1,
                registrationId = bobBundle.registrationId,
                identityKeyB64 = bobBundle.identityKeyB64,
                signedPreKeyId = bobBundle.signedPreKeyId,
                signedPreKeyB64 = bobBundle.signedPreKeyB64,
                signedPreKeySignatureB64 = bobBundle.signedPreKeySignatureB64,
                oneTimePreKeyId = bobBundle.oneTimePreKeys.first().keyId,
                oneTimePreKeyB64 = bobBundle.oneTimePreKeys.first().publicKeyB64,
                kyberPreKeyId = bobBundle.kyberPreKeys.first().keyId,
                kyberPreKeyB64 = bobBundle.kyberPreKeys.first().publicKeyB64,
                kyberPreKeySignatureB64 = bobBundle.kyberPreKeys.first().signatureB64
            )
        )

        val encBefore = aliceV1.encryptEnvelope(
            "user-2",
            1,
            EncryptedEnvelopePayload(messageId = UUID.randomUUID().toString(), body = "Before APK update")
        )
        val decBefore = bobV1.decryptEnvelope("user-1", 1, encBefore.messageType, encBefore.ciphertextB64)
        assertEquals("Before APK update", decBefore.body)

        // Simulate process restart / APK in-place update: brand-new SignalProtocolStore + SignalCryptoEngine backed by persisted DAO state
        val aliceV2 = SignalCryptoEngine(PersistentSignalProtocolStore(aliceDao), aliceDao)
        val bobV2 = SignalCryptoEngine(PersistentSignalProtocolStore(bobDao), bobDao)

        assertEquals(aliceIdentityBefore, aliceV2.prepareLocalBundle().identityKeyB64)
        assertTrue(aliceV2.hasSession("user-2", 1))
        assertTrue(bobV2.hasSession("user-1", 1))

        val encAfter = bobV2.encryptEnvelope(
            "user-1",
            1,
            EncryptedEnvelopePayload(messageId = UUID.randomUUID().toString(), body = "After APK update")
        )
        val decAfter = aliceV2.decryptEnvelope("user-2", 1, encAfter.messageType, encAfter.ciphertextB64)
        assertEquals("After APK update", decAfter.body)
    }
}
