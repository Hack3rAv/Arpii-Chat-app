package com.systemutility.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.network.dto.PendingDeviceDto
import com.systemutility.core.network.dto.SessionViewDto
import com.systemutility.core.network.interceptor.SessionRevocationBus
import com.systemutility.core.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val serverUrl: String = "",
    val showServerSettings: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val pendingApprovalDeviceId: String? = null,
    val canResetDeviceIdentity: Boolean = false,
    val lockoutRemainingSeconds: Int = 0
)

data class SessionsUiState(
    val isLoading: Boolean = false,
    val currentSessionId: String = "",
    val currentDeviceId: String = "",
    val sessions: List<SessionViewDto> = emptyList(),
    val pendingDevices: List<PendingDeviceDto> = emptyList(),
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

data class PasswordRecoveryUiState(
    val username: String = "",
    val recoveryCode: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val revocationBus: SessionRevocationBus
) : ViewModel() {

    val activeSession = authRepository.sessionState

    private val _loginState = MutableStateFlow(
        LoginUiState(serverUrl = authRepository.getConfiguredServerUrl())
    )
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _sessionsState = MutableStateFlow(SessionsUiState())
    val sessionsState: StateFlow<SessionsUiState> = _sessionsState.asStateFlow()

    private val _recoveryState = MutableStateFlow(
        PasswordRecoveryUiState(serverUrl = authRepository.getConfiguredServerUrl())
    )
    val recoveryState: StateFlow<PasswordRecoveryUiState> = _recoveryState.asStateFlow()

    init {
        viewModelScope.launch {
            revocationBus.revokedEvents.collect { reason ->
                _loginState.update { it.copy(errorMessage = reason, isLoading = false) }
            }
        }
    }

    fun updateUsername(value: String) = _loginState.update {
        it.copy(username = value, errorMessage = null, pendingApprovalDeviceId = null, canResetDeviceIdentity = false)
    }
    fun updatePassword(value: String) = _loginState.update {
        it.copy(password = value, errorMessage = null, pendingApprovalDeviceId = null, canResetDeviceIdentity = false)
    }
    fun updateServerUrl(value: String) = _loginState.update {
        it.copy(serverUrl = value, errorMessage = null, pendingApprovalDeviceId = null, canResetDeviceIdentity = false)
    }
    fun togglePasswordVisibility() = _loginState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    fun toggleServerSettings() = _loginState.update { it.copy(showServerSettings = !it.showServerSettings) }

    fun rotateDeviceIdentity() {
        authRepository.resetLocalDeviceIdentity()
        _loginState.update {
            it.copy(
                errorMessage = "Device ID rotated. Sign in again to request owner approval.",
                pendingApprovalDeviceId = null,
                canResetDeviceIdentity = false
            )
        }
    }

    fun submitLogin(onSuccess: () -> Unit) {
        val state = _loginState.value
        if (state.lockoutRemainingSeconds > 0 || state.isLoading) return
        if (state.username.isBlank() || state.password.isBlank()) {
            _loginState.update { it.copy(errorMessage = "Enter both username and password.") }
            return
        }

        viewModelScope.launch {
            _loginState.update {
                it.copy(isLoading = true, errorMessage = null, pendingApprovalDeviceId = null, canResetDeviceIdentity = false)
            }
            when (val res = authRepository.login(state.username, state.password, state.serverUrl)) {
                is AppResult.Success -> {
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            password = "",
                            errorMessage = null,
                            pendingApprovalDeviceId = null,
                            canResetDeviceIdentity = false
                        )
                    }
                    onSuccess()
                }
                is AppResult.Error -> {
                    _loginState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = res.message,
                            pendingApprovalDeviceId = if (res.code == "DEVICE_APPROVAL_REQUIRED") res.pendingDeviceId else null,
                            canResetDeviceIdentity = res.code in setOf("DEVICE_REVOKED", "DEVICE_APPROVAL_REJECTED"),
                            lockoutRemainingSeconds = res.retryAfterSeconds ?: 0
                        )
                    }
                    res.retryAfterSeconds?.let { startLockoutCountdown(it) }
                }
            }
        }
    }

    private fun startLockoutCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _loginState.update { it.copy(lockoutRemainingSeconds = remaining) }
            }
        }
    }

    fun loadSessions() {
        viewModelScope.launch {
            _sessionsState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            when (val res = authRepository.listSessions()) {
                is AppResult.Success -> {
                    _sessionsState.update {
                        it.copy(
                            isLoading = false,
                            currentSessionId = res.data.currentSessionId,
                            currentDeviceId = res.data.currentDeviceId,
                            sessions = res.data.sessions,
                            pendingDevices = res.data.pendingDevices
                        )
                    }
                }
                is AppResult.Error -> {
                    _sessionsState.update { it.copy(isLoading = false, errorMessage = res.message) }
                }
            }
        }
    }

    fun revokeSession(sessionId: String) {
        viewModelScope.launch {
            when (val res = authRepository.revokeSession(sessionId)) {
                is AppResult.Success -> {
                    _sessionsState.update { it.copy(statusMessage = "Session revoked successfully.") }
                    loadSessions()
                }
                is AppResult.Error -> _sessionsState.update { it.copy(errorMessage = res.message) }
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            when (val res = authRepository.revokeDevice(deviceId)) {
                is AppResult.Success -> {
                    _sessionsState.update { it.copy(statusMessage = "Device and associated sessions revoked.") }
                    loadSessions()
                }
                is AppResult.Error -> _sessionsState.update { it.copy(errorMessage = res.message) }
            }
        }
    }

    fun approvePendingDevice(deviceId: String) {
        viewModelScope.launch {
            when (val res = authRepository.approveDevice(deviceId)) {
                is AppResult.Success -> {
                    _sessionsState.update { it.copy(statusMessage = "Device approved. You may now sign in from that device.") }
                    loadSessions()
                }
                is AppResult.Error -> _sessionsState.update { it.copy(errorMessage = res.message) }
            }
        }
    }

    fun rejectPendingDevice(deviceId: String) {
        viewModelScope.launch {
            when (val res = authRepository.rejectDevice(deviceId)) {
                is AppResult.Success -> {
                    _sessionsState.update { it.copy(statusMessage = "Pending device enrollment rejected.") }
                    loadSessions()
                }
                is AppResult.Error -> _sessionsState.update { it.copy(errorMessage = res.message) }
            }
        }
    }

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }

    // Recovery & Change Password
    fun updateRecUsername(v: String) = _recoveryState.update { it.copy(username = v, errorMessage = null) }
    fun updateRecCode(v: String) = _recoveryState.update { it.copy(recoveryCode = v, errorMessage = null) }
    fun updateRecCurrentPass(v: String) = _recoveryState.update { it.copy(currentPassword = v, errorMessage = null) }
    fun updateRecNewPass(v: String) = _recoveryState.update { it.copy(newPassword = v, errorMessage = null) }
    fun updateRecConfirmPass(v: String) = _recoveryState.update { it.copy(confirmPassword = v, errorMessage = null) }
    fun updateRecServerUrl(v: String) = _recoveryState.update { it.copy(serverUrl = v, errorMessage = null) }

    fun submitPasswordChange() {
        val st = _recoveryState.value
        if (st.newPassword.length < 10) {
            _recoveryState.update { it.copy(errorMessage = "New password must be at least 10 characters.") }
            return
        }
        if (st.newPassword != st.confirmPassword) {
            _recoveryState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }
        viewModelScope.launch {
            _recoveryState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            when (val res = authRepository.changePassword(st.currentPassword, st.newPassword)) {
                is AppResult.Success -> _recoveryState.update {
                    it.copy(
                        isLoading = false,
                        currentPassword = "",
                        newPassword = "",
                        confirmPassword = "",
                        successMessage = "Password changed cleanly. Other active sessions revoked."
                    )
                }
                is AppResult.Error -> _recoveryState.update { it.copy(isLoading = false, errorMessage = res.message) }
            }
        }
    }

    fun submitAccountRecovery() {
        val st = _recoveryState.value
        if (st.newPassword.length < 10 || st.newPassword != st.confirmPassword) {
            _recoveryState.update { it.copy(errorMessage = "Enter matching new passwords (min 10 chars).") }
            return
        }
        viewModelScope.launch {
            _recoveryState.update { it.copy(isLoading = true, errorMessage = null, successMessage = null) }
            when (val res = authRepository.recoverAccount(st.username, st.recoveryCode, st.newPassword, st.serverUrl)) {
                is AppResult.Success -> _recoveryState.update {
                    it.copy(
                        isLoading = false,
                        recoveryCode = "",
                        newPassword = "",
                        confirmPassword = "",
                        successMessage = "Password reset cleanly via Recovery Code. You can now sign in."
                    )
                }
                is AppResult.Error -> _recoveryState.update { it.copy(isLoading = false, errorMessage = res.message) }
            }
        }
    }
}
