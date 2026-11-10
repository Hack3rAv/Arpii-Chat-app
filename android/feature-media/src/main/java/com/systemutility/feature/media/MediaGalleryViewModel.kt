package com.systemutility.feature.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.dao.MessageDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExtractedLinkItem(
    val messageId: String,
    val url: String,
    val timestamp: Long
)

data class MediaGalleryUiState(
    val visualMedia: List<AttachmentEntity> = emptyList(),
    val docsAndAudio: List<AttachmentEntity> = emptyList(),
    val links: List<ExtractedLinkItem> = emptyList(),
    val statusMessage: String? = null
)

@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    private val mediaRepository: EncryptedMediaRepository,
    messageDao: MessageDao
) : ViewModel() {

    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val _status = MutableStateFlow<String?>(null)

    private val extractedLinksFlow = combine(messageDao.observeAllMessages()) { (messages) ->
        messages.filter { !it.isDeleted }.flatMap { msg ->
            urlRegex.findAll(msg.body).map { m ->
                ExtractedLinkItem(messageId = msg.id, url = m.value, timestamp = msg.createdAt)
            }.toList()
        }.sortedByDescending { it.timestamp }
    }

    val uiState: StateFlow<MediaGalleryUiState> = combine(
        mediaRepository.observeVisualMedia(),
        mediaRepository.observeDocsAndAudio(),
        extractedLinksFlow,
        _status
    ) { visual, docs, links, status ->
        MediaGalleryUiState(
            visualMedia = visual,
            docsAndAudio = docs,
            links = links,
            statusMessage = status
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MediaGalleryUiState()
    )

    fun downloadAttachment(attachment: AttachmentEntity) {
        viewModelScope.launch {
            val res = mediaRepository.downloadAndDecrypt(attachment)
            if (res is AppResult.Error) {
                _status.value = res.message
            }
        }
    }

    fun saveToDownloads(attachment: AttachmentEntity) {
        viewModelScope.launch {
            val res = mediaRepository.saveToDownloads(attachment)
            _status.value = when (res) {
                is AppResult.Success -> res.data
                is AppResult.Error -> res.message
            }
        }
    }

    fun deleteLocalCache(attachmentId: String) {
        viewModelScope.launch {
            mediaRepository.deleteLocalCache(attachmentId)
            _status.value = "Removed decrypted copy from local vault cache."
        }
    }

    fun clearStatus() {
        _status.value = null
    }
}
