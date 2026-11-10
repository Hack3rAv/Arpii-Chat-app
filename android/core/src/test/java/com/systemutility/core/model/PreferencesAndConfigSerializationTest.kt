package com.systemutility.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesAndConfigSerializationTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `appearance, privacy, and notification preferences round-trip cleanly`() {
        val appearance = ChatAppearanceConfig(
            themeMode = ThemeMode.DARK,
            accentHex = 0xFF0284C7L,
            outgoingBubbleHex = 0xFF1D4ED8L,
            incomingBubbleHex = 0xFF1E293BL,
            bubbleCornerRadiusDp = 16,
            fontScale = 1.15f,
            wallpaperId = "obsidian_night"
        )
        val privacy = PrivacyPreferences(
            sendReadReceipts = false,
            sendTypingIndicators = false,
            showOnlineStatus = false,
            showLastSeen = true,
            disappearingTimerSeconds = DisappearingTimer.HOUR_1.seconds,
            appLockEnabled = true,
            biometricLockEnabled = true,
            screenshotProtectionEnabled = true
        )
        val notif = NotificationPreferences(
            messageSoundEnabled = false,
            callRingtoneEnabled = true,
            vibrateEnabled = true,
            showNotificationPreviews = false,
            stealthNotificationText = true,
            muteConversation = false,
            dndEnabled = true
        )

        val decodedAppearance = json.decodeFromString<ChatAppearanceConfig>(json.encodeToString(appearance))
        val decodedPrivacy = json.decodeFromString<PrivacyPreferences>(json.encodeToString(privacy))
        val decodedNotif = json.decodeFromString<NotificationPreferences>(json.encodeToString(notif))

        assertEquals(appearance, decodedAppearance)
        assertEquals(privacy, decodedPrivacy)
        assertEquals(notif, decodedNotif)
        assertTrue(decodedPrivacy.appLockEnabled)
        assertTrue(decodedPrivacy.screenshotProtectionEnabled)
        assertFalse(decodedPrivacy.sendReadReceipts)
    }

    @Test
    fun `signal envelope and attachment metadata serialize accurately`() {
        val env = EncryptedEnvelopePayload(
            action = "MESSAGE",
            messageId = "msg-123",
            kind = "VOICE",
            body = "Voice note",
            expiresInSeconds = DisappearingTimer.HOURS_24.seconds,
            attachment = EncryptedAttachmentMeta(
                attachmentId = "att-1",
                category = MediaCategory.VOICE.apiValue,
                fileName = "voice_1.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 12345L,
                r2ObjectKey = "u1/att-1.enc",
                fileKeyBase64 = "a2V5",
                baseIvBase64 = "aXY=",
                sha256CiphertextHex = "deadbeef",
                durationMs = 4500L,
                waveformCsv = "10,45,80,30"
            )
        )
        val decoded = json.decodeFromString<EncryptedEnvelopePayload>(json.encodeToString(env))
        assertEquals(env, decoded)
    }
}
