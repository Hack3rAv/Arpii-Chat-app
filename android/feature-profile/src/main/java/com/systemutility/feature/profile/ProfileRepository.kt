package com.systemutility.feature.profile

import android.net.Uri
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.UserEntity
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.model.UserProfile
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.UpdateProfileRequest
import com.systemutility.core.result.AppResult
import com.systemutility.feature.media.EncryptedMediaRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val api: SystemUtilityApi,
    private val userDao: UserDao,
    private val mediaRepository: EncryptedMediaRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    fun observeSelf(): Flow<UserEntity?> = userDao.observeSelfUser()
    fun observePartner(): Flow<UserEntity?> = userDao.observePartnerUser()

    suspend fun refreshProfiles(): AppResult<Unit> = withContext(ioDispatcher) {
        val res = safeApiCall { api.getProfiles() }
        val dto = when (res) {
            is AppResult.Error -> return@withContext res
            is AppResult.Success -> res.data
        }
        persistAndCacheAvatar(dto.selfUser, isSelf = true)
        dto.partnerUser?.let { persistAndCacheAvatar(it, isSelf = false) }
        AppResult.Success(Unit)
    }

    suspend fun updateDisplayNameAndAbout(displayName: String, about: String): AppResult<Unit> = withContext(ioDispatcher) {
        val cleanName = displayName.trim()
        if (cleanName.isEmpty()) {
            return@withContext AppResult.Error("VALIDATION_ERROR", "Display name cannot be empty.")
        }
        val res = safeApiCall {
            api.updateProfile(UpdateProfileRequest(displayName = cleanName, about = about.trim()))
        }
        when (res) {
            is AppResult.Error -> res
            is AppResult.Success -> {
                persistAndCacheAvatar(res.data.selfUser, isSelf = true)
                res.data.partnerUser?.let { persistAndCacheAvatar(it, isSelf = false) }
                AppResult.Success(Unit)
            }
        }
    }

    suspend fun uploadAvatar(sourceUri: Uri): AppResult<Unit> = withContext(ioDispatcher) {
        val upRes = mediaRepository.uploadEncryptedAvatar(sourceUri)
        val (r2Key, keyB64) = when (upRes) {
            is AppResult.Error -> return@withContext upRes
            is AppResult.Success -> upRes.data
        }

        val patchRes = safeApiCall {
            api.updateProfile(UpdateProfileRequest(avatarR2Key = r2Key, avatarKeyBase64 = keyB64))
        }
        when (patchRes) {
            is AppResult.Error -> patchRes
            is AppResult.Success -> {
                persistAndCacheAvatar(patchRes.data.selfUser, isSelf = true)
                AppResult.Success(Unit)
            }
        }
    }

    suspend fun removeAvatar(): AppResult<Unit> = withContext(ioDispatcher) {
        val patchRes = safeApiCall {
            api.updateProfile(UpdateProfileRequest(avatarR2Key = null, avatarKeyBase64 = null))
        }
        when (patchRes) {
            is AppResult.Error -> patchRes
            is AppResult.Success -> {
                val existing = userDao.getSelfUser()
                existing?.avatarLocalPath?.let { runCatching { File(it).delete() } }
                persistAndCacheAvatar(patchRes.data.selfUser, isSelf = true)
                AppResult.Success(Unit)
            }
        }
    }

    private suspend fun persistAndCacheAvatar(profile: UserProfile, isSelf: Boolean) {
        val existing = if (isSelf) userDao.getSelfUser() else userDao.getPartnerUser()
        var localPath = if (existing?.avatarR2Key == profile.avatarR2Key &&
            existing?.avatarLocalPath != null &&
            File(existing.avatarLocalPath!!).exists()
        ) {
            existing.avatarLocalPath
        } else {
            null
        }

        val r2Key = profile.avatarR2Key
        val keyB64 = profile.avatarKeyBase64
        if (localPath == null && !r2Key.isNullOrBlank() && !keyB64.isNullOrBlank()) {
            val dl = mediaRepository.downloadAndDecryptAvatar(r2Key, keyB64, profile.id)
            if (dl is AppResult.Success) {
                localPath = dl.data.absolutePath
            }
        }

        userDao.upsertUser(
            UserEntity(
                id = profile.id,
                slotId = profile.slotId,
                username = profile.username,
                displayName = profile.displayName,
                about = profile.about,
                encryptionIdentityId = profile.encryptionIdentityId,
                avatarLocalPath = localPath,
                avatarR2Key = r2Key,
                avatarKeyBase64 = keyB64,
                lastSeenAt = profile.lastSeenAt,
                privacyShowLastSeen = profile.privacyShowLastSeen,
                privacyShowOnline = profile.privacyShowOnline,
                isSelf = isSelf
            )
        )
    }
}
