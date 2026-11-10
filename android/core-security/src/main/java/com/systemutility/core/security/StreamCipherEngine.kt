package com.systemutility.core.security

import java.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedStreamResult(
    val keyBase64: String,
    val baseIvBase64: String,
    val encryptedBytes: Long,
    val sha256CiphertextHex: String
)

/**
 * Memory-bounded O(1) streaming AES-256-GCM cipher with per-chunk authentication tags
 * and monotonic chunk-index + final-chunk AAD binding to defeat truncation or reordering.
 */
@Singleton
class StreamCipherEngine @Inject constructor() {

    private val secureRandom = SecureRandom()

    fun generateKeyBytes(): ByteArray {
        return ByteArray(32).also { secureRandom.nextBytes(it) }
    }

    fun generateBaseIv(): ByteArray {
        return ByteArray(12).also { secureRandom.nextBytes(it) }
    }

    fun encryptStream(
        input: InputStream,
        output: OutputStream,
        keyBytes: ByteArray = generateKeyBytes(),
        baseIv: ByteArray = generateBaseIv()
    ): EncryptedStreamResult {
        require(keyBytes.size == 32) { "Key must be 256-bit (32 bytes)" }
        require(baseIv.size == 12) { "Base IV must be 96-bit (12 bytes)" }

        val key: SecretKey = SecretKeySpec(keyBytes, "AES")
        val md = MessageDigest.getInstance("SHA-256")
        var totalWritten = 0L

        val countingOut = object : OutputStream() {
            override fun write(b: Int) {
                output.write(b)
                md.update(b.toByte())
                totalWritten++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                output.write(b, off, len)
                md.update(b, off, len)
                totalWritten += len
            }
        }

        val dos = DataOutputStream(countingOut)
        dos.write(MAGIC_HEADER)
        dos.write(baseIv)

        val buffer = ByteArray(CHUNK_SIZE)
        var chunkIndex = 0L

        while (true) {
            val read = readFullyChunk(input, buffer)
            if (read <= 0 && chunkIndex > 0L) break

            val chunkData = if (read <= 0) ByteArray(0) else buffer.copyOfRange(0, read)
            val isLast = read < CHUNK_SIZE

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, chunkIv))
            cipher.updateAAD(buildAad(chunkIndex, isLast))

            val encryptedChunk = cipher.doFinal(chunkData)
            dos.writeInt(encryptedChunk.size)
            dos.writeBoolean(isLast)
            dos.write(encryptedChunk)

            chunkIndex++
            if (isLast) break
        }
        dos.flush()

        return EncryptedStreamResult(
            keyBase64 = Base64.getEncoder().encodeToString(keyBytes),
            baseIvBase64 = Base64.getEncoder().encodeToString(baseIv),
            encryptedBytes = totalWritten,
            sha256CiphertextHex = md.digest().joinToString("") { "%02x".format(it) }
        )
    }

    fun decryptStream(
        input: InputStream,
        output: OutputStream,
        keyBytes: ByteArray
    ) {
        require(keyBytes.size == 32) { "Key must be 256-bit (32 bytes)" }
        val key: SecretKey = SecretKeySpec(keyBytes, "AES")
        val dis = DataInputStream(input)

        val magic = ByteArray(MAGIC_HEADER.size)
        dis.readFully(magic)
        if (!magic.contentEquals(MAGIC_HEADER)) {
            throw SecurityException("Invalid encrypted stream header.")
        }

        val baseIv = ByteArray(12)
        dis.readFully(baseIv)

        var chunkIndex = 0L
        while (true) {
            val encryptedLen = dis.readInt()
            if (encryptedLen <= 0 || encryptedLen > CHUNK_SIZE + 64) {
                throw SecurityException("Corrupted encrypted chunk length: $encryptedLen")
            }
            val isLast = dis.readBoolean()
            val encryptedChunk = ByteArray(encryptedLen)
            dis.readFully(encryptedChunk)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val chunkIv = deriveChunkIv(baseIv, chunkIndex)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, chunkIv))
            cipher.updateAAD(buildAad(chunkIndex, isLast))

            val plain = cipher.doFinal(encryptedChunk)
            if (plain.isNotEmpty()) {
                output.write(plain)
            }

            chunkIndex++
            if (isLast) break
        }
        output.flush()
    }

    private fun readFullyChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun deriveChunkIv(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val iv = baseIv.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            iv[4 + i] = (iv[4 + i].toInt() xor ((chunkIndex ushr shift) and 0xFF).toInt()).toByte()
        }
        return iv
    }

    private fun buildAad(chunkIndex: Long, isLast: Boolean): ByteArray {
        val aad = ByteArray(9)
        for (i in 0 until 8) {
            aad[i] = ((chunkIndex ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        aad[8] = if (isLast) 1 else 0
        return aad
    }

    companion object {
        private const val CHUNK_SIZE = 65536
        private val MAGIC_HEADER = byteArrayOf('S'.code.toByte(), 'U'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 0x01)
    }
}
