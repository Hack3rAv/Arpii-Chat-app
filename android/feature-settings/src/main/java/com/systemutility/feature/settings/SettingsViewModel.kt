package com.systemutility.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.database.signal.SignalCryptoEngine
import com.systemutility.core.model.ChatAppearanceConfig
import com.systemutility.core.model.DisappearingTimer
import com.systemutility.core.model.NotificationPreferences
import com.systemutility.core.model.PrivacyPreferences
import com.systemutility.core.model.ThemeMode
import com.systemutility.core.model.UserProfile
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.UpdateProfileRequest
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.SecureSessionStore
import com.systemutility.feature.settings.update.AppUpdateManager
import com.systemutility.feature.settings.update.AppUpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val selfUser: UserProfile? = null,
    val partnerUser: UserProfile? = null,
    val appearance: ChatAppearanceConfig = ChatAppearanceConfig(),
    val privacy: PrivacyPreferences = PrivacyPreferences(),
    val notifications: NotificationPreferences = NotificationPreferences(),
    val hasAppPinConfigured: Boolean = false,
    val safetyNumber: String? = null,
    val isPartnerIdentityVerified: Boolean = false,
    val statusNotice: String? = null,
    val updateState: AppUpdateUiState = AppUpdateUiState()
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsDataStore: UserPreferencesDataStore,
    private val userDao: UserDao,
    private val sessionStore: SecureSessionStore,
    private val signalEngine: SignalCryptoEngine,
    private val api: SystemUtilityApi,
    private val appUpdateManager: AppUpdateManager
) : ViewModel() {

    private data class ExtraState(
        val hasPin: Boolean = false,
        val safetyNumber: String? = null,
        val isVerified: Boolean = false,
        val notice: String? = null
    )

    private val _extra = MutableStateFlow(ExtraState(hasPin = sessionStore.hasAppLockPin()))

    private val tailFlow = combine(
        prefsDataStore.notificationFlow,
        _extra,
        appUpdateManager.uiState
    ) { notif, ex, upd ->
        Triple(notif, ex, upd)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        userDao.observeSelfUser(),
        userDao.observePartnerUser(),
        prefsDataStore.appearanceFlow,
        prefsDataStore.privacyFlow,
        tailFlow
    ) { self, partner, appearance, privacy, tail ->
        val (notif, ex, upd) = tail
        SettingsUiState(
            selfUser = self?.toDomain(),
            partnerUser = partner?.toDomain(),
            appearance = appearance,
            privacy = privacy,
            notifications = notif,
            hasAppPinConfigured = ex.hasPin,
            safetyNumber = ex.safetyNumber,
            isPartnerIdentityVerified = ex.isVerified,
            statusNotice = ex.notice,
            updateState = upd
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(
            hasAppPinConfigured = sessionStore.hasAppLockPin(),
            updateState = appUpdateManager.uiState.value
        )
    )

    fun refreshSafetyNumber() {
        viewModelScope.launch {
            val self = userDao.getSelfUser() ?: return@launch
            val partner = userDao.getPartnerUser() ?: return@launch
            val fp = signalEngine.getSafetyNumber(self.id, partner.id)
            val verified = signalEngine.isPartnerIdentityVerified(partner.id)
            _extra.update { it.copy(safetyNumber = fp, isVerified = verified) }
        }
    }

    fun setPartnerVerified(verified: Boolean) {
        viewModelScope.launch {
            val partner = userDao.getPartnerUser() ?: return@launch
            signalEngine.setPartnerIdentityVerified(partner.id, verified = verified)
            _extra.update {
                it.copy(
                    isVerified = verified,
                    notice = if (verified) "Partner key marked verified." else "Partner verification cleared."
                )
            }
        }
    }

    // Appearance controls
    fun setThemeMode(mode: ThemeMode) = updateAppearance { it.copy(themeMode = mode) }
    fun setAccentHex(hex: Long) = updateAppearance { it.copy(accentHex = hex) }
    fun setOutgoingBubbleHex(hex: Long) = updateAppearance { it.copy(outgoingBubbleHex = hex) }
    fun setIncomingBubbleHex(hex: Long) = updateAppearance { it.copy(incomingBubbleHex = hex) }
    fun setCornerRadius(dp: Int) = updateAppearance { it.copy(bubbleCornerRadiusDp = dp.coerceIn(6, 24)) }
    fun setFontScale(scale: Float) = updateAppearance { it.copy(fontScale = scale.coerceIn(0.85f, 1.35f)) }
    fun setWallpaper(id: String) = updateAppearance { it.copy(wallpaperId = id) }

    private fun updateAppearance(transform: (ChatAppearanceConfig) -> ChatAppearanceConfig) {
        viewModelScope.launch {
            val cur = prefsDataStore.appearanceFlow.first()
            prefsDataStore.updateAppearance(transform(cur))
        }
    }

    // Notification controls
    fun setMessageSound(enabled: Boolean) = updateNotif { it.copy(messageSoundEnabled = enabled) }
    fun setCallRingtone(enabled: Boolean) = updateNotif { it.copy(callRingtoneEnabled = enabled) }
    fun setVibrate(enabled: Boolean) = updateNotif { it.copy(vibrateEnabled = enabled) }
    fun setShowPreviews(enabled: Boolean) = updateNotif { it.copy(showNotificationPreviews = enabled) }
    fun setStealthNotification(enabled: Boolean) = updateNotif { it.copy(stealthNotificationText = enabled) }
    fun setMuteConversation(muted: Boolean) {
        viewModelScope.launch {
            userDao.setMuted(muted)
            val cur = prefsDataStore.notificationFlow.first()
            prefsDataStore.updateNotifications(cur.copy(muteConversation = muted))
        }
    }

    fun setDnd(enabled: Boolean) = updateNotif { it.copy(dndEnabled = enabled) }

    private fun updateNotif(transform: (NotificationPreferences) -> NotificationPreferences) {
        viewModelScope.launch {
            val cur = prefsDataStore.notificationFlow.first()
            prefsDataStore.updateNotifications(transform(cur))
        }
    }

    // Privacy & Security controls
    fun setReadReceipts(enabled: Boolean) = updatePrivacy { it.copy(sendReadReceipts = enabled) }
    fun setTypingIndicators(enabled: Boolean) = updatePrivacy { it.copy(sendTypingIndicators = enabled) }
    fun setDisappearingTimer(timer: DisappearingTimer) = updatePrivacy { it.copy(disappearingTimerSeconds = timer.seconds) }
    fun setScreenshotProtection(enabled: Boolean) = updatePrivacy { it.copy(screenshotProtectionEnabled = enabled) }

    fun setShowOnline(enabled: Boolean) {
        viewModelScope.launch {
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(cur.copy(showOnlineStatus = enabled))
            safeApiCall { api.updateProfile(UpdateProfileRequest(privacyShowOnline = enabled)) }
        }
    }

    fun setShowLastSeen(enabled: Boolean) {
        viewModelScope.launch {
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(cur.copy(showLastSeen = enabled))
            safeApiCall { api.updateProfile(UpdateProfileRequest(privacyShowLastSeen = enabled)) }
        }
    }

    fun configureAppPin(pin: String) {
        if (pin.length < 4 || !pin.all { it.isDigit() }) {
            _extra.update { it.copy(notice = "PIN must be 4 to 6 numeric digits.") }
            return
        }
        viewModelScope.launch {
            sessionStore.setAppLockPin(pin)
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(cur.copy(appLockEnabled = true))
            _extra.update { it.copy(hasPin = true, notice = "App Lock PIN enabled.") }
        }
    }

    fun disableAppLock() {
        viewModelScope.launch {
            sessionStore.clearAppLockPin()
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(cur.copy(appLockEnabled = false, biometricLockEnabled = false))
            _extra.update { it.copy(hasPin = false, notice = "App Lock disabled.") }
        }
    }

    fun setBiometricLock(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !sessionStore.hasAppLockPin()) {
                _extra.update { it.copy(notice = "Set an App Lock PIN first before enabling biometrics.") }
                return@launch
            }
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(cur.copy(biometricLockEnabled = enabled))
        }
    }

    private fun updatePrivacy(transform: (PrivacyPreferences) -> PrivacyPreferences) {
        viewModelScope.launch {
            val cur = prefsDataStore.privacyFlow.first()
            prefsDataStore.updatePrivacy(transform(cur))
        }
    }

    fun verifyPin(pin: String): Boolean = sessionStore.verifyAppLockPin(pin)

    fun clearNotice() {
        _extra.update { it.copy(notice = null) }
    }

    // App Update (GitHub Releases) controls
    fun saveGithubUpdateRepo(repo: String) {
        val res = appUpdateManager.saveGithubRepo(repo)
        if (res is AppResult.Error) {
            _extra.update { it.copy(notice = res.message) }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) = appUpdateManager.setAutoCheckUpdates(enabled)
    fun checkForUpdatesNow() = appUpdateManager.checkForUpdates(force = true)
    fun downloadAndInstallUpdate() = appUpdateManager.downloadAndInstallUpdate()
    fun installDownloadedUpdate() = appUpdateManager.installDownloadedApk()
    fun dismissUpdateDialog() = appUpdateManager.dismissPromptDialog()
    fun skipAvailableUpdate() = appUpdateManager.skipCurrentRelease()
    fun clearUpdateNotices() = appUpdateManager.clearNotices()
}
