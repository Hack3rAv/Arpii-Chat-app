package com.systemutility.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.entity.BackupMetaEntity
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

data class BackupUiState(
    val recoveryKeyHex: String = "",
    val isBusy: Boolean = false,
    val localHistory: List<BackupMetaEntity> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val repository: BackupRepository
) : ViewModel() {

    private data class VolatileState(
        val recoveryKeyHex: String = "",
        val isBusy: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null
    )

    private val _volatile = MutableStateFlow(
        VolatileState(recoveryKeyHex = repository.getOrCreateRecoveryKeyHex())
    )

    val uiState: StateFlow<BackupUiState> = combine(
        repository.observeBackupHistory(),
        _volatile
    ) { history, vol ->
        BackupUiState(
            recoveryKeyHex = vol.recoveryKeyHex,
            isBusy = vol.isBusy,
            localHistory = history,
            statusMessage = vol.statusMessage,
            errorMessage = vol.errorMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BackupUiState(recoveryKeyHex = repository.getOrCreateRecoveryKeyHex())
    )

    fun importRecoveryKey(hexInput: String) {
        when (val res = repository.setRecoveryKeyHex(hexInput)) {
            is AppResult.Success -> _volatile.update {
                it.copy(
                    recoveryKeyHex = res.data,
                    statusMessage = "Backup Recovery Key updated.",
                    errorMessage = null
                )
            }
            is AppResult.Error -> _volatile.update { it.copy(errorMessage = res.message) }
        }
    }

    fun createLocalEncryptedSnapshot() {
        viewModelScope.launch {
            _volatile.update { it.copy(isBusy = true, errorMessage = null, statusMessage = null) }
            when (val res = repository.createLocalEncryptedSnapshot()) {
                is AppResult.Success -> _volatile.update {
                    it.copy(isBusy = false, statusMessage = "Saved encrypted local snapshot: ${res.data.name}")
                }
                is AppResult.Error -> _volatile.update {
                    it.copy(isBusy = false, errorMessage = res.message)
                }
            }
        }
    }

    fun restoreLocalSnapshot(record: BackupMetaEntity) {
        viewModelScope.launch {
            _volatile.update { it.copy(isBusy = true, errorMessage = null, statusMessage = null) }
            when (val res = repository.restoreLocalEncryptedSnapshot(record.driveFileId, record.sha256CiphertextHex)) {
                is AppResult.Success -> _volatile.update {
                    it.copy(
                        isBusy = false,
                        statusMessage = "Restored ${res.data} encrypted messages & preferences from local snapshot."
                    )
                }
                is AppResult.Error -> _volatile.update {
                    it.copy(isBusy = false, errorMessage = res.message)
                }
            }
        }
    }

    fun clearNotices() {
        _volatile.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}
