package com.systemutility.feature.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.model.UserProfile
import com.systemutility.core.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val selfUser: UserProfile? = null,
    val partnerUser: UserProfile? = null,
    val draftName: String = "",
    val draftAbout: String = "",
    val hasDraftInitialized: Boolean = false,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: ProfileRepository
) : ViewModel() {

    private val _editState = MutableStateFlow(EditDraftState())

    private data class EditDraftState(
        val draftName: String = "",
        val draftAbout: String = "",
        val initialized: Boolean = false,
        val isSaving: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null
    )

    val uiState: StateFlow<ProfileUiState> = combine(
        repository.observeSelf(),
        repository.observePartner(),
        _editState
    ) { self, partner, edit ->
        val sDomain = self?.toDomain()
        val pDomain = partner?.toDomain()
        val nameVal = if (edit.initialized) edit.draftName else sDomain?.displayName.orEmpty()
        val aboutVal = if (edit.initialized) edit.draftAbout else sDomain?.about.orEmpty()
        ProfileUiState(
            selfUser = sDomain,
            partnerUser = pDomain,
            draftName = nameVal,
            draftAbout = aboutVal,
            hasDraftInitialized = edit.initialized,
            isSaving = edit.isSaving,
            statusMessage = edit.statusMessage,
            errorMessage = edit.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProfileUiState()
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.refreshProfiles()
        }
    }

    fun updateDraftName(value: String) {
        val currentAbout = uiState.value.draftAbout
        _editState.update {
            it.copy(draftName = value, draftAbout = currentAbout, initialized = true, errorMessage = null, statusMessage = null)
        }
    }

    fun updateDraftAbout(value: String) {
        val currentName = uiState.value.draftName
        _editState.update {
            it.copy(draftName = currentName, draftAbout = value, initialized = true, errorMessage = null, statusMessage = null)
        }
    }

    fun saveProfile() {
        val name = uiState.value.draftName
        val about = uiState.value.draftAbout
        viewModelScope.launch {
            _editState.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }
            when (val res = repository.updateDisplayNameAndAbout(name, about)) {
                is AppResult.Success -> _editState.update {
                    it.copy(isSaving = false, initialized = false, statusMessage = "Profile updated.")
                }
                is AppResult.Error -> _editState.update {
                    it.copy(isSaving = false, errorMessage = res.message)
                }
            }
        }
    }

    fun uploadAvatarUri(uri: Uri) {
        viewModelScope.launch {
            _editState.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }
            when (val res = repository.uploadAvatar(uri)) {
                is AppResult.Success -> _editState.update {
                    it.copy(isSaving = false, statusMessage = "Encrypted profile picture updated.")
                }
                is AppResult.Error -> _editState.update {
                    it.copy(isSaving = false, errorMessage = res.message)
                }
            }
        }
    }

    fun removeAvatar() {
        viewModelScope.launch {
            _editState.update { it.copy(isSaving = true, errorMessage = null, statusMessage = null) }
            when (val res = repository.removeAvatar()) {
                is AppResult.Success -> _editState.update {
                    it.copy(isSaving = false, statusMessage = "Profile picture removed.")
                }
                is AppResult.Error -> _editState.update {
                    it.copy(isSaving = false, errorMessage = res.message)
                }
            }
        }
    }

    fun clearNotices() {
        _editState.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
