package com.systemutility.core.database.signal

import com.systemutility.core.database.dao.SignalStoreDao
import com.systemutility.core.database.entity.SignalKyberPreKeyEntity
import com.systemutility.core.database.entity.SignalLocalIdentityEntity
import com.systemutility.core.database.entity.SignalPeerIdentityEntity
import com.systemutility.core.database.entity.SignalPreKeyEntity
import com.systemutility.core.database.entity.SignalSessionEntity
import com.systemutility.core.database.entity.SignalSignedPreKeyEntity
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room/SQLCipher-backed implementation of SignalProtocolStore (libsignal 0.86.5 + PQXDH).
 */
@Singleton
class PersistentSignalProtocolStore @Inject constructor(
    private val dao: SignalStoreDao
) : SignalProtocolStore {

    private val senderKeyCache = ConcurrentHashMap<String, SenderKeyRecord>()

    private fun addrKey(address: SignalProtocolAddress): String = "${address.name}:${address.deviceId}"

    @Synchronized
    fun ensureLocalIdentity(): SignalLocalIdentityEntity = runBlocking {
        val existing = dao.getLocalIdentity()
        if (existing != null) return@runBlocking existing

        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)
        val created = SignalLocalIdentityEntity(
            id = 1,
            registrationId = registrationId,
            serializedIdentityKeyPair = identityKeyPair.serialize()
        )
        dao.saveLocalIdentity(created)
        created
    }

    override fun getIdentityKeyPair(): IdentityKeyPair {
        val local = ensureLocalIdentity()
        return IdentityKeyPair(local.serializedIdentityKeyPair)
    }

    override fun getLocalRegistrationId(): Int {
        return ensureLocalIdentity().registrationId
    }

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange = runBlocking {
        val key = addrKey(address)
        val existing = dao.getPeerIdentity(key)
        val newBytes = identityKey.serialize()
        if (existing == null) {
            dao.savePeerIdentity(
                SignalPeerIdentityEntity(
                    addressKey = key,
                    serializedIdentityKey = newBytes,
                    trusted = true,
                    verifiedAt = null
                )
            )
            return@runBlocking IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        if (existing.serializedIdentityKey.contentEquals(newBytes)) {
            return@runBlocking IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
        dao.savePeerIdentity(
            SignalPeerIdentityEntity(
                addressKey = key,
                serializedIdentityKey = newBytes,
                trusted = true,
                verifiedAt = null
            )
        )
        IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean = runBlocking {
        val existing = dao.getPeerIdentity(addrKey(address)) ?: return@runBlocking true
        if (existing.serializedIdentityKey.contentEquals(identityKey.serialize())) {
            return@runBlocking existing.trusted
        }
        // Only auto-reject mismatched keys if the user previously verified this peer manually.
        // If unverified, allow key rotation update and clear verifiedAt in saveIdentity().
        existing.verifiedAt == null
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? = runBlocking {
        val existing = dao.getPeerIdentity(addrKey(address)) ?: return@runBlocking null
        IdentityKey(existing.serializedIdentityKey)
    }

    override fun loadPreKey(preKeyId: Int): PreKeyRecord = runBlocking {
        val entity = dao.getPreKey(preKeyId)
            ?: throw InvalidKeyIdException("PreKey $preKeyId not found")
        PreKeyRecord(entity.serializedRecord)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = runBlocking {
        dao.savePreKey(SignalPreKeyEntity(preKeyId = preKeyId, serializedRecord = record.serialize()))
    }

    override fun containsPreKey(preKeyId: Int): Boolean = runBlocking {
        dao.containsPreKey(preKeyId)
    }

    override fun removePreKey(preKeyId: Int) = runBlocking {
        dao.removePreKey(preKeyId)
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord = runBlocking {
        val entity = dao.getSignedPreKey(signedPreKeyId)
            ?: throw InvalidKeyIdException("SignedPreKey $signedPreKeyId not found")
        SignedPreKeyRecord(entity.serializedRecord)
    }

    override fun loadSignedPreKeys(): MutableList<SignedPreKeyRecord> = runBlocking {
        dao.getAllSignedPreKeys()
            .map { SignedPreKeyRecord(it.serializedRecord) }
            .toMutableList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = runBlocking {
        dao.saveSignedPreKey(
            SignalSignedPreKeyEntity(signedPreKeyId = signedPreKeyId, serializedRecord = record.serialize())
        )
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = runBlocking {
        dao.containsSignedPreKey(signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) = runBlocking {
        dao.removeSignedPreKey(signedPreKeyId)
    }

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord = runBlocking {
        val entity = dao.getKyberPreKey(kyberPreKeyId)
            ?: throw InvalidKeyIdException("KyberPreKey $kyberPreKeyId not found")
        KyberPreKeyRecord(entity.serializedRecord)
    }

    override fun loadKyberPreKeys(): MutableList<KyberPreKeyRecord> = runBlocking {
        dao.getAllKyberPreKeys()
            .map { KyberPreKeyRecord(it.serializedRecord) }
            .toMutableList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = runBlocking {
        dao.saveKyberPreKey(
            SignalKyberPreKeyEntity(
                kyberPreKeyId = kyberPreKeyId,
                serializedRecord = record.serialize(),
                used = false
            )
        )
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = runBlocking {
        dao.getKyberPreKey(kyberPreKeyId) != null
    }

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey
    ) = runBlocking {
        if (kyberPreKeyId < SignalCryptoEngine.LAST_RESORT_KYBER_ID) {
            dao.markKyberPreKeyUsed(kyberPreKeyId)
        }
    }

    override fun loadSession(address: SignalProtocolAddress): SessionRecord = runBlocking {
        val entity = dao.getSession(addrKey(address))
        if (entity != null) {
            SessionRecord(entity.serializedRecord)
        } else {
            SessionRecord()
        }
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): MutableList<SessionRecord> =
        runBlocking {
            addresses.map { address ->
                val entity = dao.getSession(addrKey(address))
                    ?: throw NoSessionException("No session for ${addrKey(address)}")
                SessionRecord(entity.serializedRecord)
            }.toMutableList()
        }

    override fun getSubDeviceSessions(name: String): MutableList<Int> = runBlocking {
        dao.getSubDeviceAddresses(name)
            .mapNotNull { key -> key.substringAfter(':', "").toIntOrNull() }
            .toMutableList()
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = runBlocking {
        dao.saveSession(
            SignalSessionEntity(
                addressKey = addrKey(address),
                serializedRecord = record.serialize()
            )
        )
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean = runBlocking {
        dao.containsSession(addrKey(address))
    }

    override fun deleteSession(address: SignalProtocolAddress) = runBlocking {
        dao.deleteSession(addrKey(address))
    }

    override fun deleteAllSessions(name: String) = runBlocking {
        dao.deleteAllSessions(name)
    }

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        senderKeyCache["${addrKey(sender)}:$distributionId"] = record
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? {
        return senderKeyCache["${addrKey(sender)}:$distributionId"]
    }
}
