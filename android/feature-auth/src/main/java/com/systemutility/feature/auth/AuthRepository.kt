package com.systemutility.feature.auth

import android.os.Build
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.ConversationEntity
import com.systemutility.core.database.entity.UserEntity
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.model.SessionCredentials
import com.systemutility.core.model.UserProfile
import com.systemutility.core.network.BuildConfig
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.*
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.Argon2Kdf
import com.systemutility.core.security.SecureSessionStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: SystemUtilityApi,
    private val sessionStore: SecureSessionStore,
    private val argon2Kdf: Argon2Kdf,
    private val userDao: UserDao,
    private val wsClient: RealtimeWebSocketClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    val sessionState: StateFlow<SessionCredentials?> = sessionStore.sessionFlow

    fun getConfiguredServerUrl(): String =
        sessionStore.getServerBaseUrl(BuildConfig.DEFAULT_BASE_URL)

    suspend fun login(
        username: String,
        password: String,
        serverUrl: String
    ): AppResult<SessionCredentials> = withContext(ioDispatcher) {
        val cleanUrl = serverUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            return@withContext AppResult.Error("INVALID_URL", "Server endpoint URL is required.")
        }
        sessionStore.setServerBaseUrl(cleanUrl)

        val cleanUser = username.trim().lowercase()
        val saltResult = safeApiCall { api.getSalt(SaltRequest(cleanUser)) }
        val saltResponse = when (saltResult) {
            is AppResult.Error -> return@withContext saltResult
            is AppResult.Success -> saltResult.data
        }

        val digestHex = argon2Kdf.deriveAuthDigestHex(
            secret = password,
            saltHex = saltResponse.saltHex,
            memoryKiB = saltResponse.kdf.memoryKiB,
            iterations = saltResponse.kdf.iterations,
            parallelism = saltResponse.kdf.parallelism
        )

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }
        val stableDeviceId = sessionStore.getOrCreateStableDeviceId()

        val loginResult = safeApiCall {
            api.login(
                LoginRequest(
                    username = cleanUser,
                    clientDigestHex = digestHex,
                    deviceName = deviceName,
                    deviceId = stableDeviceId,
                    platform = "android"
                )
            )
        }

        return@withContext when (loginResult) {
            is AppResult.Error -> loginResult
            is AppResult.Success -> {
                val res = loginResult.data
                val creds = SessionCredentials(
                    userId = res.selfUser.id,
                    slotId = res.selfUser.slotId,
                    username = res.selfUser.username,
                    token = res.token,
                    sessionId = res.sessionId,
                    deviceId = res.deviceId,
                    signalDeviceId = res.signalDeviceId,
                    expiresAt = res.expiresAt,
                    baseUrl = cleanUrl
                )
                sessionStore.saveSession(creds)
                persistUsersToLocalDb(res.selfUser, res.partnerUser)
                wsClient.connect()
                SafeLogger.i("Auth", "Authenticated slot=${res.selfUser.slotId} device=${res.signalDeviceId}")
                AppResult.Success(creds)
            }
        }
    }

    suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): AppResult<Unit> = withContext(ioDispatcher) {
        val session = sessionStore.getSession()
            ?: return@withContext AppResult.Error("UNAUTHENTICATED", "No active session.")

        if (newPassword.length < 10) {
            return@withContext AppResult.Error("WEAK_PASSWORD", "New password must be at least 10 characters.")
        }

        val saltResult = safeApiCall { api.getSalt(SaltRequest(session.username)) }
        val saltResponse = when (saltResult) {
            is AppResult.Error -> return@withContext saltResult
            is AppResult.Success -> saltResult.data
        }

        val currentDigest = argon2Kdf.deriveAuthDigestHex(
            secret = currentPassword,
            saltHex = saltResponse.saltHex,
            memoryKiB = saltResponse.kdf.memoryKiB,
            iterations = saltResponse.kdf.iterations,
            parallelism = saltResponse.kdf.parallelism
        )

        val newSaltHex = argon2Kdf.generateSaltHex(16)
        val newDigest = argon2Kdf.deriveAuthDigestHex(newPassword, newSaltHex)

        safeApiCall {
            api.changePassword(
                ChangePasswordRequest(
                    currentClientDigestHex = currentDigest,
                    newSaltHex = newSaltHex,
                    newClientDigestHex = newDigest
                )
            )
        }
    }

    suspend fun recoverAccount(
        username: String,
        recoveryCode: String,
        newPassword: String,
        serverUrl: String
    ): AppResult<Unit> = withContext(ioDispatcher) {
        sessionStore.setServerBaseUrl(serverUrl.trim().trimEnd('/'))
        val cleanUser = username.trim().lowercase()
        val saltResult = safeApiCall { api.getSalt(SaltRequest(cleanUser)) }
        val saltResponse = when (saltResult) {
            is AppResult.Error -> return@withContext saltResult
            is AppResult.Success -> saltResult.data
        }

        val recoveryDigest = argon2Kdf.deriveAuthDigestHex(
            secret = recoveryCode.trim().uppercase(),
            saltHex = saltResponse.saltHex,
            memoryKiB = saltResponse.kdf.memoryKiB,
            iterations = saltResponse.kdf.iterations,
            parallelism = saltResponse.kdf.parallelism
        )

        val newSaltHex = argon2Kdf.generateSaltHex(16)
        val newDigest = argon2Kdf.deriveAuthDigestHex(newPassword, newSaltHex)

        safeApiCall {
            api.recoverAccount(
                RecoverAccountRequest(
                    username = cleanUser,
                    recoveryDigestHex = recoveryDigest,
                    newSaltHex = newSaltHex,
                    newClientDigestHex = newDigest
                )
            )
        }
    }

    suspend fun listSessions(): AppResult<SessionsListResponse> = withContext(ioDispatcher) {
        safeApiCall { api.listSessions() }
    }

    suspend fun revokeSession(sessionId: String): AppResult<Unit> = withContext(ioDispatcher) {
        val res = safeApiCall { api.revokeSession(sessionId) }
        if (res is AppResult.Success && sessionStore.getSession()?.sessionId == sessionId) {
            wsClient.disconnect()
            sessionStore.clearSession()
        }
        res
    }

    suspend fun revokeDevice(deviceId: String): AppResult<Unit> = withContext(ioDispatcher) {
        val res = safeApiCall { api.revokeDevice(deviceId) }
        if (res is AppResult.Success && sessionStore.getSession()?.deviceId == deviceId) {
            wsClient.disconnect()
            sessionStore.clearSession()
            sessionStore.rotateDeviceId()
        }
        res
    }

    suspend fun approveDevice(deviceId: String): AppResult<Unit> = withContext(ioDispatcher) {
        safeApiCall { api.approveDevice(deviceId) }
    }

    suspend fun rejectDevice(deviceId: String): AppResult<Unit> = withContext(ioDispatcher) {
        safeApiCall { api.rejectDevice(deviceId) }
    }

    fun resetLocalDeviceIdentity(): String {
        return sessionStore.rotateDeviceId()
    }

    suspend fun logout() = withContext(ioDispatcher) {
        runCatching { api.logout() }
        wsClient.disconnect()
        sessionStore.clearSession()
    }

    private suspend fun persistUsersToLocalDb(self: UserProfile, partner: UserProfile?) {
        val existingSelf = userDao.getSelfUser()
        val selfLocalAvatar = if (existingSelf?.avatarR2Key == self.avatarR2Key) existingSelf?.avatarLocalPath else null
        userDao.upsertUser(
            UserEntity(
                id = self.id,
                slotId = self.slotId,
                username = self.username,
                displayName = self.displayName,
                about = self.about,
                encryptionIdentityId = self.encryptionIdentityId,
                avatarLocalPath = selfLocalAvatar,
                avatarR2Key = self.avatarR2Key,
                avatarKeyBase64 = self.avatarKeyBase64,
                lastSeenAt = self.lastSeenAt,
                privacyShowLastSeen = self.privacyShowLastSeen,
                privacyShowOnline = self.privacyShowOnline,
                isSelf = true
            )
        )
        if (partner != null) {
            val existingPartner = userDao.getPartnerUser()
            val partnerLocalAvatar = if (existingPartner?.avatarR2Key == partner.avatarR2Key) existingPartner?.avatarLocalPath else null
            userDao.upsertUser(
                UserEntity(
                    id = partner.id,
                    slotId = partner.slotId,
                    username = partner.username,
                    displayName = partner.displayName,
                    about = partner.about,
                    encryptionIdentityId = partner.encryptionIdentityId,
                    avatarLocalPath = partnerLocalAvatar,
                    avatarR2Key = partner.avatarR2Key,
                    avatarKeyBase64 = partner.avatarKeyBase64,
                    lastSeenAt = partner.lastSeenAt,
                    privacyShowLastSeen = partner.privacyShowLastSeen,
                    privacyShowOnline = partner.privacyShowOnline,
                    isSelf = false
                )
            )
            val existingConv = userDao.getPrimaryConversation()
            userDao.upsertConversation(
                existingConv?.copy(partnerUserId = partner.id)
                    ?: ConversationEntity(
                        id = "primary_pair",
                        partnerUserId = partner.id
                    )
            )
        }
    }
}
