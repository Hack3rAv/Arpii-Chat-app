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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

private class InMemorySignalStoreDao : SignalStoreDao {
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

    override suspend fun getSignedPreKey(signedPreKeyId: Int): SignalSignedPreKeyEntity? =
        signedPreKeys[signedPreKeyId]

    override suspend fun getAllSignedPreKeys(): List<SignalSignedPreKeyEntity> = signedPreKeys.values.toList()
    override suspend fun saveSignedPreKey(entity: SignalSignedPreKeyEntity) {
        signedPreKeys[entity.signedPreKeyId] = entity
    }

    override suspend fun containsSignedPreKey(signedPreKeyId: Int): Boolean =
        signedPreKeys.containsKey(signedPreKeyId)

    override suspend fun removeSignedPreKey(signedPreKeyId: Int) {
        signedPreKeys.remove(signedPreKeyId)
    }

    override suspend fun getKyberPreKey(kyberPreKeyId: Int): SignalKyberPreKeyEntity? =
        kyberPreKeys[kyberPreKeyId]

    override suspend fun getAllKyberPreKeys(): List<SignalKyberPreKeyEntity> = kyberPreKeys.values.toList()
    override suspend fun saveKyberPreKey(entity: SignalKyberPreKeyEntity) {
        kyberPreKeys[entity.kyberPreKeyId] = entity
    }

    override suspend fun markKyberPreKeyUsed(kyberPreKeyId: Int) {
        kyberPreKeys[kyberPreKeyId]?.let {
            kyberPreKeys[kyberPreKeyId] = it.copy(used = true)
        }
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

class SignalRoundTripTest {

    @Test
    fun `pqxdh handshake and bidirectional double ratchet messaging succeed`() = runBlocking {
        val aliceDao = InMemorySignalStoreDao()
        val aliceEngine = SignalCryptoEngine(PersistentSignalProtocolStore(aliceDao), aliceDao)

        val bobDao = InMemorySignalStoreDao()
        val bobEngine = SignalCryptoEngine(PersistentSignalProtocolStore(bobDao), bobDao)

        val bobBundle = bobEngine.prepareLocalBundle()
        aliceEngine.prepareLocalBundle()

        val bobOtk = bobBundle.oneTimePreKeys.first()
        val bobKyber = bobBundle.kyberPreKeys.first()

        aliceEngine.processPartnerBundle(
            RemoteBundleInput(
                userId = "user-2",
                deviceId = 1,
                registrationId = bobBundle.registrationId,
                identityKeyB64 = bobBundle.identityKeyB64,
                signedPreKeyId = bobBundle.signedPreKeyId,
                signedPreKeyB64 = bobBundle.signedPreKeyB64,
                signedPreKeySignatureB64 = bobBundle.signedPreKeySignatureB64,
                oneTimePreKeyId = bobOtk.keyId,
                oneTimePreKeyB64 = bobOtk.publicKeyB64,
                kyberPreKeyId = bobKyber.keyId,
                kyberPreKeyB64 = bobKyber.publicKeyB64,
                kyberPreKeySignatureB64 = bobKyber.signatureB64
            )
        )

        // Alice sends first PreKeySignalMessage
        val msg1 = EncryptedEnvelopePayload(
            messageId = UUID.randomUUID().toString(),
            body = "Secret hello with PQXDH"
        )
        val enc1 = aliceEngine.encryptEnvelope("user-2", 1, msg1)
        assertEquals("signal_prekey", enc1.messageType)

        // Bob decrypts PreKeySignalMessage and establishes session automatically
        val dec1 = bobEngine.decryptEnvelope("user-1", 1, enc1.messageType, enc1.ciphertextB64)
        assertEquals(msg1.body, dec1.body)
        assertTrue(bobEngine.hasSession("user-1", 1))

        // Bob replies over Double Ratchet (now standard signal_whisper)
        val msg2 = EncryptedEnvelopePayload(
            messageId = UUID.randomUUID().toString(),
            body = "Loud and clear on the ratchet"
        )
        val enc2 = bobEngine.encryptEnvelope("user-1", 1, msg2)
        assertEquals("signal_whisper", enc2.messageType)

        val dec2 = aliceEngine.decryptEnvelope("user-2", 1, enc2.messageType, enc2.ciphertextB64)
        assertEquals(msg2.body, dec2.body)

        // Both compute identical 60-digit Safety Numbers
        val aliceFp = aliceEngine.getSafetyNumber("user-1", "user-2", 1)
        val bobFp = bobEngine.getSafetyNumber("user-2", "user-1", 1)
        assertNotNull(aliceFp)
        assertEquals(aliceFp, bobFp)
    }
}
