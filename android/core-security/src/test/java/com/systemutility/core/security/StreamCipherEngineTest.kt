package com.systemutility.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import kotlin.random.Random

class StreamCipherEngineTest {

    private val engine = StreamCipherEngine()

    @Test
    fun `encrypt and decrypt multi-chunk stream matches plaintext`() {
        val original = Random(42).nextBytes(200_000) // spans > 3 64KB chunks
        val key = engine.generateKeyBytes()
        val iv = engine.generateBaseIv()

        val encryptedOut = ByteArrayOutputStream()
        engine.encryptStream(ByteArrayInputStream(original), encryptedOut, key, iv)

        val decryptedOut = ByteArrayOutputStream()
        engine.decryptStream(ByteArrayInputStream(encryptedOut.toByteArray()), decryptedOut, key)

        assertArrayEquals(original, decryptedOut.toByteArray())
    }

    @Test
    fun `tampered ciphertext fails GCM authentication tag`() {
        val original = "Secret message inside attachment".toByteArray()
        val key = engine.generateKeyBytes()

        val encryptedOut = ByteArrayOutputStream()
        engine.encryptStream(ByteArrayInputStream(original), encryptedOut, key)
        val ciphertext = encryptedOut.toByteArray()

        // Flip 1 bit near the end of ciphertext
        ciphertext[ciphertext.size - 10] = (ciphertext[ciphertext.size - 10].toInt() xor 0x01).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            engine.decryptStream(ByteArrayInputStream(ciphertext), ByteArrayOutputStream(), key)
        }
    }
}
