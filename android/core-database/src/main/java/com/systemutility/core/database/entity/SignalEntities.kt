package com.systemutility.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "signal_local_identity")
data class SignalLocalIdentityEntity(
    @PrimaryKey val id: Int = 1,
    val registrationId: Int,
    val serializedIdentityKeyPair: ByteArray
)

@Entity(tableName = "signal_peer_identities")
data class SignalPeerIdentityEntity(
    @PrimaryKey val addressKey: String, // "$name:$deviceId"
    val serializedIdentityKey: ByteArray,
    val trusted: Boolean = true,
    val verifiedAt: Long? = null
)

@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(
    @PrimaryKey val preKeyId: Int,
    val serializedRecord: ByteArray
)

@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey val signedPreKeyId: Int,
    val serializedRecord: ByteArray
)

@Entity(tableName = "signal_kyber_prekeys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey val kyberPreKeyId: Int,
    val serializedRecord: ByteArray,
    val used: Boolean = false
)

@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(
    @PrimaryKey val addressKey: String, // "$name:$deviceId"
    val serializedRecord: ByteArray
)
