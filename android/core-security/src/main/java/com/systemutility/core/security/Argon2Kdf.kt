package com.systemutility.core.security

import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Argon2Kdf @Inject constructor() {

    private val argon2Kt = Argon2Kt()
    private val secureRandom = SecureRandom()

    fun generateSaltHex(byteLength: Int = 16): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return bytes.toLowerHex()
    }

    fun deriveAuthDigestHex(
        secret: String,
        saltHex: String,
        memoryKiB: Int = DEFAULT_MEMORY_KIB,
        iterations: Int = DEFAULT_ITERATIONS,
        parallelism: Int = DEFAULT_PARALLELISM
    ): String {
        val saltBytes = hexToBytes(saltHex)
        val result = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = secret.toByteArray(Charsets.UTF_8),
            salt = saltBytes,
            tCostInIterations = iterations,
            mCostInKibibyte = memoryKiB,
            parallelism = parallelism,
            hashLengthInBytes = 32
        )
        return result.rawHashAsByteArray().toLowerHex()
    }

    fun deriveRawKeyBytes(secret: String, saltBytes: ByteArray): ByteArray {
        val result = argon2Kt.hash(
            mode = Argon2Mode.ARGON2_ID,
            password = secret.toByteArray(Charsets.UTF_8),
            salt = saltBytes,
            tCostInIterations = DEFAULT_ITERATIONS,
            mCostInKibibyte = DEFAULT_MEMORY_KIB,
            parallelism = DEFAULT_PARALLELISM,
            hashLengthInBytes = 32
        )
        return result.rawHashAsByteArray()
    }

    companion object {
        const val DEFAULT_MEMORY_KIB = 65536
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1

        fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0) { "Invalid hex string length" }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
