package com.systemutility.core.database.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.systemutility.core.model.ChatAppearanceConfig
import com.systemutility.core.model.NotificationPreferences
import com.systemutility.core.model.PrivacyPreferences
import com.systemutility.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "system_utility_prefs")

@Singleton
class UserPreferencesDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.prefsDataStore

    val appearanceFlow: Flow<ChatAppearanceConfig> = dataStore.data.map { prefs ->
        ChatAppearanceConfig(
            themeMode = runCatching { ThemeMode.valueOf(prefs[THEME_MODE] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            accentHex = prefs[ACCENT_HEX] ?: 0xFF0F766EL,
            outgoingBubbleHex = prefs[OUTGOING_BUBBLE_HEX] ?: 0xFF0F766EL,
            incomingBubbleHex = prefs[INCOMING_BUBBLE_HEX] ?: 0xFF1F2937L,
            bubbleCornerRadiusDp = prefs[BUBBLE_CORNER] ?: 18,
            fontScale = prefs[FONT_SCALE] ?: 1.0f,
            wallpaperId = prefs[WALLPAPER_ID] ?: "minimal_slate",
            customWallpaperUri = prefs[CUSTOM_WALLPAPER_URI]
        )
    }

    val privacyFlow: Flow<PrivacyPreferences> = dataStore.data.map { prefs ->
        PrivacyPreferences(
            sendReadReceipts = prefs[READ_RECEIPTS] ?: true,
            sendTypingIndicators = prefs[TYPING_INDICATORS] ?: true,
            showOnlineStatus = prefs[SHOW_ONLINE] ?: true,
            showLastSeen = prefs[SHOW_LAST_SEEN] ?: true,
            disappearingTimerSeconds = prefs[DISAPPEARING_SECONDS] ?: 0L,
            appLockEnabled = prefs[APP_LOCK_ENABLED] ?: false,
            biometricLockEnabled = prefs[BIOMETRIC_LOCK_ENABLED] ?: false,
            screenshotProtectionEnabled = prefs[SCREENSHOT_PROTECTION] ?: false
        )
    }

    val notificationFlow: Flow<NotificationPreferences> = dataStore.data.map { prefs ->
        NotificationPreferences(
            messageSoundEnabled = prefs[NOTIF_MSG_SOUND] ?: true,
            callRingtoneEnabled = prefs[NOTIF_CALL_RINGTONE] ?: true,
            vibrateEnabled = prefs[NOTIF_VIBRATE] ?: true,
            showNotificationPreviews = prefs[NOTIF_PREVIEWS] ?: false,
            stealthNotificationText = prefs[NOTIF_STEALTH] ?: true,
            muteConversation = prefs[NOTIF_MUTED] ?: false,
            dndEnabled = prefs[NOTIF_DND] ?: false
        )
    }

    val autoBackupEnabledFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_BACKUP_ENABLED] ?: false
    }

    val autoBackupAccountEmailFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[DRIVE_ACCOUNT_EMAIL]
    }

    val githubUpdateRepoFlow: Flow<String> = dataStore.data.map { prefs ->
        prefs[GITHUB_UPDATE_REPO].orEmpty()
    }

    val autoCheckUpdatesFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_CHECK_UPDATES] ?: true
    }

    val lastUpdateCheckAtFlow: Flow<Long> = dataStore.data.map { prefs ->
        prefs[LAST_UPDATE_CHECK_AT] ?: 0L
    }

    val skippedUpdateTagFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[SKIPPED_UPDATE_TAG]
    }

    suspend fun getLastSyncedServerSeqOnce(): Long =
        dataStore.data.first()[LAST_SYNCED_SERVER_SEQ] ?: 0L

    suspend fun advanceLastSyncedServerSeq(seq: Long) {
        if (seq <= 0L) return
        dataStore.edit { prefs ->
            val current = prefs[LAST_SYNCED_SERVER_SEQ] ?: 0L
            if (seq > current) {
                prefs[LAST_SYNCED_SERVER_SEQ] = seq
            }
        }
    }

    suspend fun getPartnerSignalDeviceIdOnce(): Int =
        dataStore.data.first()[PARTNER_SIGNAL_DEVICE_ID] ?: 1

    suspend fun setPartnerSignalDeviceId(deviceId: Int) {
        if (deviceId <= 0) return
        dataStore.edit { prefs ->
            prefs[PARTNER_SIGNAL_DEVICE_ID] = deviceId
        }
    }

    suspend fun setGithubUpdateRepo(repo: String) {
        val clean = repo.trim()
        dataStore.edit { prefs ->
            if (clean.isEmpty()) prefs.remove(GITHUB_UPDATE_REPO) else prefs[GITHUB_UPDATE_REPO] = clean
        }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setLastUpdateCheckAt(timestampMs: Long) {
        dataStore.edit { it[LAST_UPDATE_CHECK_AT] = timestampMs }
    }

    suspend fun setSkippedUpdateTag(tag: String?) {
        dataStore.edit {
            if (tag.isNullOrBlank()) it.remove(SKIPPED_UPDATE_TAG) else it[SKIPPED_UPDATE_TAG] = tag.trim()
        }
    }

    suspend fun updateAppearance(config: ChatAppearanceConfig) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = config.themeMode.name
            prefs[ACCENT_HEX] = config.accentHex
            prefs[OUTGOING_BUBBLE_HEX] = config.outgoingBubbleHex
            prefs[INCOMING_BUBBLE_HEX] = config.incomingBubbleHex
            prefs[BUBBLE_CORNER] = config.bubbleCornerRadiusDp
            prefs[FONT_SCALE] = config.fontScale
            prefs[WALLPAPER_ID] = config.wallpaperId
            val customWallpaper = config.customWallpaperUri
            if (customWallpaper != null) {
                prefs[CUSTOM_WALLPAPER_URI] = customWallpaper
            } else {
                prefs.remove(CUSTOM_WALLPAPER_URI)
            }
        }
    }

    suspend fun updatePrivacy(privacy: PrivacyPreferences) {
        dataStore.edit { prefs ->
            prefs[READ_RECEIPTS] = privacy.sendReadReceipts
            prefs[TYPING_INDICATORS] = privacy.sendTypingIndicators
            prefs[SHOW_ONLINE] = privacy.showOnlineStatus
            prefs[SHOW_LAST_SEEN] = privacy.showLastSeen
            prefs[DISAPPEARING_SECONDS] = privacy.disappearingTimerSeconds
            prefs[APP_LOCK_ENABLED] = privacy.appLockEnabled
            prefs[BIOMETRIC_LOCK_ENABLED] = privacy.biometricLockEnabled
            prefs[SCREENSHOT_PROTECTION] = privacy.screenshotProtectionEnabled
        }
    }

    suspend fun updateNotifications(notif: NotificationPreferences) {
        dataStore.edit { prefs ->
            prefs[NOTIF_MSG_SOUND] = notif.messageSoundEnabled
            prefs[NOTIF_CALL_RINGTONE] = notif.callRingtoneEnabled
            prefs[NOTIF_VIBRATE] = notif.vibrateEnabled
            prefs[NOTIF_PREVIEWS] = notif.showNotificationPreviews
            prefs[NOTIF_STEALTH] = notif.stealthNotificationText
            prefs[NOTIF_MUTED] = notif.muteConversation
            prefs[NOTIF_DND] = notif.dndEnabled
        }
    }

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[AUTO_BACKUP_ENABLED] = enabled }
    }

    suspend fun setDriveAccountEmail(email: String?) {
        dataStore.edit {
            if (email != null) it[DRIVE_ACCOUNT_EMAIL] = email else it.remove(DRIVE_ACCOUNT_EMAIL)
        }
    }

    suspend fun exportAllForBackup(): Map<String, String> {
        val prefs = dataStore.data.first()
        return prefs.asMap().entries.associate { (k, v) -> k.name to v.toString() }
    }

    suspend fun restoreFromBackup(raw: Map<String, String>) {
        dataStore.edit { prefs ->
            raw[THEME_MODE.name]?.let { prefs[THEME_MODE] = it }
            raw[ACCENT_HEX.name]?.toLongOrNull()?.let { prefs[ACCENT_HEX] = it }
            raw[OUTGOING_BUBBLE_HEX.name]?.toLongOrNull()?.let { prefs[OUTGOING_BUBBLE_HEX] = it }
            raw[INCOMING_BUBBLE_HEX.name]?.toLongOrNull()?.let { prefs[INCOMING_BUBBLE_HEX] = it }
            raw[BUBBLE_CORNER.name]?.toIntOrNull()?.let { prefs[BUBBLE_CORNER] = it }
            raw[FONT_SCALE.name]?.toFloatOrNull()?.let { prefs[FONT_SCALE] = it }
            raw[WALLPAPER_ID.name]?.let { prefs[WALLPAPER_ID] = it }
            raw[READ_RECEIPTS.name]?.toBooleanStrictOrNull()?.let { prefs[READ_RECEIPTS] = it }
            raw[TYPING_INDICATORS.name]?.toBooleanStrictOrNull()?.let { prefs[TYPING_INDICATORS] = it }
            raw[SHOW_ONLINE.name]?.toBooleanStrictOrNull()?.let { prefs[SHOW_ONLINE] = it }
            raw[SHOW_LAST_SEEN.name]?.toBooleanStrictOrNull()?.let { prefs[SHOW_LAST_SEEN] = it }
            raw[DISAPPEARING_SECONDS.name]?.toLongOrNull()?.let { prefs[DISAPPEARING_SECONDS] = it }
            raw[NOTIF_STEALTH.name]?.toBooleanStrictOrNull()?.let { prefs[NOTIF_STEALTH] = it }
        }
    }

    companion object {
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val ACCENT_HEX = longPreferencesKey("accent_hex")
        private val OUTGOING_BUBBLE_HEX = longPreferencesKey("outgoing_bubble_hex")
        private val INCOMING_BUBBLE_HEX = longPreferencesKey("incoming_bubble_hex")
        private val BUBBLE_CORNER = intPreferencesKey("bubble_corner_dp")
        private val FONT_SCALE = floatPreferencesKey("font_scale")
        private val WALLPAPER_ID = stringPreferencesKey("wallpaper_id")
        private val CUSTOM_WALLPAPER_URI = stringPreferencesKey("custom_wallpaper_uri")

        private val READ_RECEIPTS = booleanPreferencesKey("read_receipts")
        private val TYPING_INDICATORS = booleanPreferencesKey("typing_indicators")
        private val SHOW_ONLINE = booleanPreferencesKey("show_online")
        private val SHOW_LAST_SEEN = booleanPreferencesKey("show_last_seen")
        private val DISAPPEARING_SECONDS = longPreferencesKey("disappearing_seconds")
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        private val SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")

        private val NOTIF_MSG_SOUND = booleanPreferencesKey("notif_msg_sound")
        private val NOTIF_CALL_RINGTONE = booleanPreferencesKey("notif_call_ringtone")
        private val NOTIF_VIBRATE = booleanPreferencesKey("notif_vibrate")
        private val NOTIF_PREVIEWS = booleanPreferencesKey("notif_previews")
        private val NOTIF_STEALTH = booleanPreferencesKey("notif_stealth")
        private val NOTIF_MUTED = booleanPreferencesKey("notif_muted")
        private val NOTIF_DND = booleanPreferencesKey("notif_dnd")

        private val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        private val DRIVE_ACCOUNT_EMAIL = stringPreferencesKey("drive_account_email")

        private val LAST_SYNCED_SERVER_SEQ = longPreferencesKey("last_synced_server_seq")
        private val PARTNER_SIGNAL_DEVICE_ID = intPreferencesKey("partner_signal_device_id")

        private val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
        private val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val LAST_UPDATE_CHECK_AT = longPreferencesKey("last_update_check_at")
        private val SKIPPED_UPDATE_TAG = stringPreferencesKey("skipped_update_tag")
    }
}
