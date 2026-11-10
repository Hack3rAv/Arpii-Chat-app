package com.systemutility.core.database.signal

import com.systemutility.core.database.dao.SignalStoreDao
import com.systemutility.core.model.EncryptedEnvelopePayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

data class GeneratedOneTimeKey(val keyId: Int, val publicKeyB64: String)
data class GeneratedKyberKey(val keyId: Int, val publicKeyB64: String, val signatureB64: String)

data class LocalBundleSnapshot(
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyB64: String,
    val signedPreKeySignatureB64: String,
    val kyberLastResortId: Int,
    val kyberLastResortB64: String,
    val kyberLastResortSigB64: String,
    val oneTimePreKeys: List<GeneratedOneTimeKey>,
    val kyberPreKeys: List<GeneratedKyberKey>
)

data class RemoteBundleInput(
    val userId: String,
    val deviceId: Int,
    val registrationId: Int,
    val identityKeyB64: String,
    val signedPreKeyId: Int,
    val signedPreKeyB64: String,
    val signedPreKeySignatureB64: String,
    val oneTimePreKeyId: Int?,
    val oneTimePreKeyB64: String?,
    val kyberPreKeyId: Int,
    val kyberPreKeyB64: String,
    val kyberPreKeySignatureB64: String
)

data class EncryptedEnvelopeResult(
    val messageType: String, // "signal_prekey" or "signal_whisper"
    val ciphertextB64: String
)

/**
 * Thread-safe orchestrator for Signal Protocol (PQXDH + Double Ratchet)
 * session establishment, envelope encryption/decryption, and Safety Number verification.
 */
@Singleton
class SignalCryptoEngine @Inject constructor(
    private val store: PersistentSignalProtocolStore,
    private val signalDao: SignalStoreDao
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun b64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64Decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    suspend fun prepareLocalBundle(minOneTimeCount: Int = 20): LocalBundleSnapshot = mutex.withLock {
        val identityPair = store.identityKeyPair
        val registrationId = store.localRegistrationId
        val now = System.currentTimeMillis()

        // Ensure signed prekey exists
        val existingSigned = store.loadSignedPreKeys()
        val signedRecord = if (existingSigned.isNotEmpty()) {
            existingSigned.maxBy { it.id }
        } else {
            val spkId = 1
            val kp = ECKeyPair.generate()
            val sig = identityPair.privateKey.calculateSignature(kp.publicKey.serialize())
            SignedPreKeyRecord(spkId, now, kp, sig).also {
                store.storeSignedPreKey(spkId, it)
            }
        }

        // Ensure Kyber-1024 last-resort prekey exists
        val lastResortRecord = if (store.containsKyberPreKey(LAST_RESORT_KYBER_ID)) {
            store.loadKyberPreKey(LAST_RESORT_KYBER_ID)
        } else {
            val kemPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val sig = identityPair.privateKey.calculateSignature(kemPair.publicKey.serialize())
            KyberPreKeyRecord(LAST_RESORT_KYBER_ID, now, kemPair, sig).also {
                store.storeKyberPreKey(LAST_RESORT_KYBER_ID, it)
            }
        }

        // Replenish EC one-time prekeys up to batch target
        val currentOtkCount = signalDao.countPreKeys()
        if (currentOtkCount < minOneTimeCount) {
            var nextId = signalDao.maxPreKeyId() + 1
            val needed = minOneTimeCount - currentOtkCount
            repeat(needed) {
                val record = PreKeyRecord(nextId, ECKeyPair.generate())
                store.storePreKey(nextId, record)
                nextId++
            }
        }

        // Replenish Kyber one-time prekeys
        val currentKyberCount = signalDao.countUnusedKyberPreKeys()
        if (currentKyberCount < 10) {
            var nextKyberId = signalDao.maxOneTimeKyberPreKeyId() + 1
            val needed = 10 - currentKyberCount
            repeat(needed) {
                val kemPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val sig = identityPair.privateKey.calculateSignature(kemPair.publicKey.serialize())
                val record = KyberPreKeyRecord(nextKyberId, now, kemPair, sig)
                store.storeKyberPreKey(nextKyberId, record)
                nextKyberId++
            }
        }

        val allOtks = signalDao.getAllPreKeys().map { entity ->
            val rec = PreKeyRecord(entity.serializedRecord)
            GeneratedOneTimeKey(rec.id, b64Encode(rec.keyPair.publicKey.serialize()))
        }

        val unusedKyber = signalDao.getAllKyberPreKeys()
            .filter { !it.used && it.kyberPreKeyId < LAST_RESORT_KYBER_ID }
            .map { entity ->
                val rec = KyberPreKeyRecord(entity.serializedRecord)
                GeneratedKyberKey(
                    keyId = rec.id,
                    publicKeyB64 = b64Encode(rec.keyPair.publicKey.serialize()),
                    signatureB64 = b64Encode(rec.signature)
                )
            }

        LocalBundleSnapshot(
            registrationId = registrationId,
            identityKeyB64 = b64Encode(identityPair.publicKey.serialize()),
            signedPreKeyId = signedRecord.id,
            signedPreKeyB64 = b64Encode(signedRecord.keyPair.publicKey.serialize()),
            signedPreKeySignatureB64 = b64Encode(signedRecord.signature),
            kyberLastResortId = lastResortRecord.id,
            kyberLastResortB64 = b64Encode(lastResortRecord.keyPair.publicKey.serialize()),
            kyberLastResortSigB64 = b64Encode(lastResortRecord.signature),
            oneTimePreKeys = allOtks,
            kyberPreKeys = unusedKyber
        )
    }

    suspend fun hasSession(partnerUserId: String, partnerDeviceId: Int): Boolean = mutex.withLock {
        store.containsSession(SignalProtocolAddress(partnerUserId, partnerDeviceId))
    }

    suspend fun processPartnerBundle(bundle: RemoteBundleInput) = mutex.withLock {
        val address = SignalProtocolAddress(bundle.userId, bundle.deviceId)
        val ecPreKey = bundle.oneTimePreKeyB64?.let { ECPublicKey(b64Decode(it)) }
        val ecPreKeyId = bundle.oneTimePreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            bundle.deviceId,
            ecPreKeyId,
            ecPreKey,
            bundle.signedPreKeyId,
            ECPublicKey(b64Decode(bundle.signedPreKeyB64)),
            b64Decode(bundle.signedPreKeySignatureB64),
            IdentityKey(b64Decode(bundle.identityKeyB64)),
            bundle.kyberPreKeyId,
            KEMPublicKey(b64Decode(bundle.kyberPreKeyB64)),
            b64Decode(bundle.kyberPreKeySignatureB64)
        )
        val builder = SessionBuilder(store, address)
        builder.process(preKeyBundle)
    }

    suspend fun encryptEnvelope(
        partnerUserId: String,
        partnerDeviceId: Int,
        payload: EncryptedEnvelopePayload
    ): EncryptedEnvelopeResult = mutex.withLock {
        val address = SignalProtocolAddress(partnerUserId, partnerDeviceId)
        val cipher = SessionCipher(store, address)
        val plainBytes = json.encodeToString(EncryptedEnvelopePayload.serializer(), payload)
            .toByteArray(StandardCharsets.UTF_8)
        val encrypted: CiphertextMessage = cipher.encrypt(plainBytes)
        val typeStr = if (encrypted.type == CiphertextMessage.PREKEY_TYPE) "signal_prekey" else "signal_whisper"
        EncryptedEnvelopeResult(
            messageType = typeStr,
            ciphertextB64 = b64Encode(encrypted.serialize())
        )
    }

    suspend fun decryptEnvelope(
        senderUserId: String,
        senderDeviceId: Int,
        messageTypeHint: String,
        ciphertextB64: String
    ): EncryptedEnvelopePayload = mutex.withLock {
        val address = SignalProtocolAddress(senderUserId, senderDeviceId)
        val cipher = SessionCipher(store, address)
        val rawBytes = b64Decode(ciphertextB64)

        val plainBytes = when (messageTypeHint) {
            "signal_prekey" -> cipher.decrypt(PreKeySignalMessage(rawBytes))
            "signal_whisper" -> cipher.decrypt(SignalMessage(rawBytes))
            else -> runCatching { cipher.decrypt(PreKeySignalMessage(rawBytes)) }
                .getOrElse { cipher.decrypt(SignalMessage(rawBytes)) }
        }

        val decodedStr = String(plainBytes, StandardCharsets.UTF_8)
        json.decodeFromString(EncryptedEnvelopePayload.serializer(), decodedStr)
    }

    suspend fun resetPartnerSessionAndIdentity(partnerUserId: String, partnerDeviceId: Int) = mutex.withLock {
        store.deleteSession(SignalProtocolAddress(partnerUserId, partnerDeviceId))
    }

    suspend fun getSafetyNumber(
        selfUserId: String,
        partnerUserId: String,
        partnerDeviceId: Int = 1
    ): String? = mutex.withLock {
        val partnerAddr = SignalProtocolAddress(partnerUserId, partnerDeviceId)
        val partnerIdKey = store.getIdentity(partnerAddr) ?: return@withLock null
        val localIdKey = store.identityKeyPair.publicKey
        val generator = NumericFingerprintGenerator(5200)
        val fp = generator.createFor(
            2,
            selfUserId.toByteArray(StandardCharsets.UTF_8),
            localIdKey,
            partnerUserId.toByteArray(StandardCharsets.UTF_8),
            partnerIdKey
        )
        val raw = fp.displayableFingerprint.displayText
        // Format into 12 readable blocks of 5 digits
        raw.chunked(5).joinToString(" ")
    }

    suspend fun isPartnerIdentityVerified(partnerUserId: String, partnerDeviceId: Int = 1): Boolean {
        val peer = signalDao.getPeerIdentity("$partnerUserId:$partnerDeviceId") ?: return false
        return peer.verifiedAt != null
    }

    suspend fun setPartnerIdentityVerified(partnerUserId: String, partnerDeviceId: Int = 1, verified: Boolean) {
        signalDao.setPeerVerified(
            addressKey = "$partnerUserId:$partnerDeviceId",
            verifiedAt = if (verified) System.currentTimeMillis() else null
        )
    }

    companion object {
        const val LAST_RESORT_KYBER_ID = 1_000_000
    }
}
