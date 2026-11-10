package com.systemutility.feature.backup

import com.systemutility.core.security.EncryptedStreamResult
import com.systemutility.core.security.StreamCipherEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure JVM + Android codec that GZIP-compresses BackupArchivePayload JSON and encrypts/decrypts
 * using StreamCipherEngine AES-256-GCM with independent 256-bit Recovery Keys and pre-decryption SHA-256 checks.
 */
@Singleton
class BackupArchiveCodec @Inject constructor(
    private val cipherEngine: StreamCipherEngine
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeAndEncrypt(
        payload: BackupArchivePayload,
        destFile: File,
        recoveryKeyBytes: ByteArray
    ): EncryptedStreamResult {
        require(recoveryKeyBytes.size == 32) { "Backup Recovery Key must be 32 bytes (64 hex characters)." }

        val jsonStr = json.encodeToString(payload)
        val compressed = ByteArrayOutputStream().use { baos ->
            GZIPOutputStream(baos).use { gz ->
                gz.write(jsonStr.toByteArray(StandardCharsets.UTF_8))
            }
            baos.toByteArray()
        }

        return ByteArrayInputStream(compressed).use { input ->
            FileOutputStream(destFile).use { output ->
                cipherEngine.encryptStream(
                    input = input,
                    output = output,
                    keyBytes = recoveryKeyBytes
                )
            }
        }
    }

    fun verifyAndDecrypt(
        encryptedFile: File,
        recoveryKeyBytes: ByteArray,
        expectedSha256Hex: String? = null
    ): BackupArchivePayload {
        require(recoveryKeyBytes.size == 32) { "Backup Recovery Key must be 32 bytes (64 hex characters)." }
        if (!encryptedFile.exists() || encryptedFile.length() == 0L) {
            throw IllegalArgumentException("Encrypted backup archive does not exist.")
        }

        if (!expectedSha256Hex.isNullOrBlank()) {
            val actualSha = computeFileSha256Hex(encryptedFile)
            if (!actualSha.equals(expectedSha256Hex.trim(), ignoreCase = true)) {
                throw SecurityException("Encrypted backup checksum mismatch. Archive may be tampered or corrupted.")
            }
        }

        val compressedBytes = ByteArrayOutputStream().use { plainOut ->
            FileInputStream(encryptedFile).use { encIn ->
                cipherEngine.decryptStream(encIn, plainOut, recoveryKeyBytes)
            }
            plainOut.toByteArray()
        }

        val jsonStr = GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { gz ->
            String(gz.readBytes(), StandardCharsets.UTF_8)
        }
        return json.decodeFromString(BackupArchivePayload.serializer(), jsonStr)
    }

    fun computeFileSha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16384)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().replace(" ", "").replace("-", "").lowercase()
            require(clean.length == 64 && clean.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Recovery key must be exactly 64 hexadecimal characters (256-bit key)."
            }
            return ByteArray(32) { idx ->
                clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }
        }

        fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun formatDisplayKey(hex: String): String =
            hex.uppercase().replace(" ", "").chunked(8).joinToString(" ")
    }
}
