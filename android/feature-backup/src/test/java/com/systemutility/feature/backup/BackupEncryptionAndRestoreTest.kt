package com.systemutility.feature.backup

import com.systemutility.core.security.StreamCipherEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupEncryptionAndRestoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun encryptedBackupRoundTripAndTamperRejection() {
        val cipherEngine = StreamCipherEngine()
        val codec = BackupArchiveCodec(cipherEngine)

        val recoveryKey = cipherEngine.generateKeyBytes()
        val wrongKey = cipherEngine.generateKeyBytes()

        val payload = BackupArchivePayload(
            version = 1,
            createdAt = 1_794_192_000_000L,
            users = listOf(
                BackupUserRecord(
                    id = "user-1",
                    slotId = 1,
                    username = "partner_one",
                    displayName = "Alex",
                    about = "Online",
                    encryptionIdentityId = "sig_1",
                    isSelf = true
                )
            ),
            messages = listOf(
                BackupMessageRecord(
                    id = "m-1",
                    conversationId = "primary_pair",
                    senderUserId = "user-1",
                    recipientUserId = "user-2",
                    kind = "TEXT",
                    body = "Encrypted memory preserved cleanly.",
                    status = "READ",
                    serverSeq = 42L,
                    createdAt = 1_794_192_000_000L,
                    updatedAt = 1_794_192_000_500L
                )
            ),
            preferences = mapOf("theme_mode" to "DARK", "bubble_corner_dp" to "20")
        )

        val archiveFile = File(tempFolder.root, "backup_test.suenc")
        val encRes = codec.encodeAndEncrypt(payload, archiveFile, recoveryKey)
        assertTrue(archiveFile.length() > 0L)

        // 1. Valid decrypt with exact key & SHA-256 passes
        val decoded = codec.verifyAndDecrypt(archiveFile, recoveryKey, encRes.sha256CiphertextHex)
        assertEquals(1, decoded.messages.size)
        assertEquals("Encrypted memory preserved cleanly.", decoded.messages.first().body)
        assertEquals("DARK", decoded.preferences["theme_mode"])

        // 2. Wrong recovery key is rejected
        try {
            codec.verifyAndDecrypt(archiveFile, wrongKey, encRes.sha256CiphertextHex)
            fail("Expected decryption to fail with wrong key")
        } catch (_: Throwable) {
            // Expected GCM auth tag mismatch
        }

        // 3. Bit-flipped archive triggers SHA-256 verification error before decryption
        val bytes = archiveFile.readBytes()
        bytes[bytes.size - 10] = (bytes[bytes.size - 10].toInt() xor 0xFF).toByte()
        archiveFile.writeBytes(bytes)

        try {
            codec.verifyAndDecrypt(archiveFile, recoveryKey, encRes.sha256CiphertextHex)
            fail("Expected checksum mismatch on tampered archive")
        } catch (sec: SecurityException) {
            assertTrue(sec.message?.contains("checksum") == true)
        }
    }
}
