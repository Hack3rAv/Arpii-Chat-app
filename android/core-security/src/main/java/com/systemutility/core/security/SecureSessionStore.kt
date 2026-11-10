package com.systemutility.core.security

import com.systemutility.core.model.SessionCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSessionStore @Inject constructor(
    private val keystoreManager: AndroidKeystoreManager,
    private val argon2Kdf: Argon2Kdf
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs get() = keystoreManager.encryptedPrefs

    private val _sessionFlow = MutableStateFlow(loadSession())
    val sessionFlow: StateFlow<SessionCredentials?> = _sessionFlow.asStateFlow()

    fun getSession(): SessionCredentials? = _sessionFlow.value

    fun saveSession(credentials: SessionCredentials) {
        val raw = json.encodeToString(credentials)
        prefs.edit()
            .putString(KEY_SESSION_JSON, raw)
            .putString(KEY_DEVICE_ID, credentials.deviceId)
            .putString(KEY_SERVER_URL, credentials.baseUrl)
            .apply()
        _sessionFlow.value = credentials
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_JSON).apply()
        _sessionFlow.value = null
    }

    fun getOrCreateStableDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun rotateDeviceId(): String {
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    fun getServerBaseUrl(defaultUrl: String): String {
        val saved = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
        return if (saved == null || saved == LEGACY_PLACEHOLDER_URL) defaultUrl else saved
    }

    fun setServerBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
    }

    fun setAppLockPin(pin: String) {
        val salt = argon2Kdf.generateSaltHex(16)
        val digest = argon2Kdf.deriveAuthDigestHex(pin, salt, memoryKiB = 32768, iterations = 2)
        prefs.edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, digest)
            .apply()
    }

    fun verifyAppLockPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val expected = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val actual = argon2Kdf.deriveAuthDigestHex(pin, salt, memoryKiB = 32768, iterations = 2)
        return constantTimeEquals(actual, expected)
    }

    fun hasAppLockPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun clearAppLockPin() {
        prefs.edit().remove(KEY_PIN_SALT).remove(KEY_PIN_HASH).apply()
    }

    fun saveBackupRecoveryKey(recoveryKeyHex: String) {
        prefs.edit().putString(KEY_BACKUP_RECOVERY, recoveryKeyHex).apply()
    }

    fun getBackupRecoveryKey(): String? = prefs.getString(KEY_BACKUP_RECOVERY, null)

    private fun loadSession(): SessionCredentials? {
        val raw = prefs.getString(KEY_SESSION_JSON, null) ?: return null
        return runCatching { json.decodeFromString<SessionCredentials>(raw) }.getOrNull()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    companion object {
        private const val LEGACY_PLACEHOLDER_URL = "https://system-utility.your-subdomain.workers.dev"
        private const val KEY_SESSION_JSON = "active_session_v1"
        private const val KEY_DEVICE_ID = "stable_device_uuid"
        private const val KEY_SERVER_URL = "server_base_url"
        private const val KEY_PIN_SALT = "app_lock_pin_salt"
        private const val KEY_PIN_HASH = "app_lock_pin_hash"
        private const val KEY_BACKUP_RECOVERY = "drive_backup_recovery_key"
    }
}
