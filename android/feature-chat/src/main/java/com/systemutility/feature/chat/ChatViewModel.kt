package com.systemutility.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.ReactionEntity
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.database.signal.SignalCryptoEngine
import com.systemutility.core.model.ChatAppearanceConfig
import com.systemutility.core.model.DisappearingTimer
import com.systemutility.core.model.MediaCategory
import com.systemutility.core.model.UserProfile
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.network.ws.WsConnectionState
import com.systemutility.core.result.AppResult
import com.systemutility.feature.media.EncryptedMediaRepository
import com.systemutility.feature.voice.VoicePlaybackState
import com.systemutility.feature.voice.VoicePlayerManager
import com.systemutility.feature.voice.VoiceRecorderManager
import com.systemutility.feature.voice.VoiceRecordingState
import java.io.File
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val selfUser: UserProfile? = null,
    val partnerUser: UserProfile? = null,
    val messages: List<MessageEntity> = emptyList(),
    val reactionsByMessage: Map<String, List<ReactionEntity>> = emptyMap(),
    val attachmentsByMessage: Map<String, AttachmentEntity> = emptyMap(),
    val pinnedMessage: MessageEntity? = null,
    val appearance: ChatAppearanceConfig = ChatAppearanceConfig(),
    val disappearingSeconds: Long = 0L,
    val isMuted: Boolean = false,
    val isPartnerOnline: Boolean = false,
    val isPartnerTyping: Boolean = false,
    val wsState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val draftText: String = "",
    val replyingTo: MessageEntity? = null,
    val editingMessage: MessageEntity? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val safetyNumber: String? = null,
    val isIdentityVerified: Boolean = false,
    val infoBanner: String? = null,
    val voiceRecording: VoiceRecordingState = VoiceRecordingState(),
    val voicePlayback: VoicePlaybackState = VoicePlaybackState()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    userDao: UserDao,
    prefsDataStore: UserPreferencesDataStore,
    wsClient: RealtimeWebSocketClient,
    private val signalEngine: SignalCryptoEngine,
    private val mediaRepository: EncryptedMediaRepository,
    private val voiceRecorder: VoiceRecorderManager,
    private val voicePlayer: VoicePlayerManager
) : ViewModel() {

    private val _composerState = MutableStateFlow(ComposerState())
    private var typingDebounceJob: Job? = null

    init {
        chatRepository.startRealtimeObserver()
        chatRepository.setChatVisible(true)
        viewModelScope.launch {
            chatRepository.syncFromServer()
            chatRepository.flushOutbox()
        }
    }

    override fun onCleared() {
        chatRepository.setChatVisible(false)
        voiceRecorder.cancelRecording()
        voicePlayer.stop()
        super.onCleared()
    }

    private data class ComposerState(
        val draftText: String = "",
        val replyingTo: MessageEntity? = null,
        val editingMessage: MessageEntity? = null,
        val searchActive: Boolean = false,
        val searchQuery: String = "",
        val safetyNumber: String? = null,
        val isIdentityVerified: Boolean = false,
        val infoBanner: String? = null
    )

    private val coreDataFlow = combine(
        userDao.observeSelfUser(),
        userDao.observePartnerUser(),
        chatRepository.observeMessages(),
        chatRepository.observeReactions(),
        chatRepository.observePinnedMessage()
    ) { self, partner, msgs, reactions, pinned ->
        CoreChatData(
            self = self?.toDomain(),
            partner = partner?.toDomain(),
            messages = msgs,
            reactionsByMsg = reactions.groupBy { it.messageId },
            pinnedMessage = pinned
        )
    }

    private val statusFlow = combine(
        prefsDataStore.appearanceFlow,
        prefsDataStore.privacyFlow,
        userDao.observePrimaryConversation(),
        chatRepository.partnerOnline,
        chatRepository.partnerTyping
    ) { appearance, privacy, conv, online, typing ->
        StatusChatData(
            appearance = appearance,
            disappearingSeconds = privacy.disappearingTimerSeconds,
            isMuted = conv?.isMuted == true,
            online = online,
            typing = typing
        )
    }

    private val voiceFlow = combine(
        voiceRecorder.state,
        voicePlayer.state
    ) { rec, play -> rec to play }

    val uiState: StateFlow<ChatUiState> = combine(
        coreDataFlow,
        statusFlow,
        combine(chatRepository.observeAttachments(), voiceFlow) { att, vf -> att to vf },
        wsClient.connectionState,
        _composerState
    ) { core, status, mediaAndVoice, ws, comp ->
        val (attachments, vf) = mediaAndVoice
        ChatUiState(
            selfUser = core.self,
            partnerUser = core.partner,
            messages = core.messages,
            reactionsByMessage = core.reactionsByMsg,
            attachmentsByMessage = attachments.associateBy { it.messageId },
            pinnedMessage = core.pinnedMessage,
            appearance = status.appearance,
            disappearingSeconds = status.disappearingSeconds,
            isMuted = status.isMuted,
            isPartnerOnline = status.online,
            isPartnerTyping = status.typing,
            wsState = ws,
            draftText = comp.draftText,
            replyingTo = comp.replyingTo,
            editingMessage = comp.editingMessage,
            searchActive = comp.searchActive,
            searchQuery = comp.searchQuery,
            safetyNumber = comp.safetyNumber,
            isIdentityVerified = comp.isIdentityVerified,
            infoBanner = comp.infoBanner,
            voiceRecording = vf.first,
            voicePlayback = vf.second
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ChatUiState()
    )

    fun onDraftChange(text: String) {
        _composerState.update { it.copy(draftText = text, infoBanner = null) }
        val nonEmpty = text.isNotBlank()
        typingDebounceJob?.cancel()
        typingDebounceJob = viewModelScope.launch {
            chatRepository.sendTypingIndicator(nonEmpty)
            if (nonEmpty) {
                delay(3_000L)
                chatRepository.sendTypingIndicator(false)
            }
        }
    }

    fun sendCurrentDraft() {
        val comp = _composerState.value
        val trimmed = comp.draftText.trim()
        if (trimmed.isEmpty()) return

        _composerState.update { it.copy(draftText = "", replyingTo = null, editingMessage = null) }
        typingDebounceJob?.cancel()

        viewModelScope.launch {
            chatRepository.sendTypingIndicator(false)
            val res = if (comp.editingMessage != null) {
                chatRepository.editMessage(comp.editingMessage.id, trimmed)
            } else {
                chatRepository.sendTextMessage(
                    body = trimmed,
                    replyToMessageId = comp.replyingTo?.id
                )
            }
            if (res is AppResult.Error) {
                _composerState.update { it.copy(infoBanner = res.message) }
            }
        }
    }

    fun startReply(message: MessageEntity) {
        _composerState.update { it.copy(replyingTo = message, editingMessage = null) }
    }

    fun startEdit(message: MessageEntity) {
        _composerState.update {
            it.copy(editingMessage = message, replyingTo = null, draftText = message.body)
        }
    }

    fun clearReplyOrEdit() {
        _composerState.update { it.copy(replyingTo = null, editingMessage = null) }
    }

    fun toggleSearch(active: Boolean) {
        _composerState.update { it.copy(searchActive = active, searchQuery = if (active) it.searchQuery else "") }
    }

    fun updateSearchQuery(query: String) {
        _composerState.update { it.copy(searchQuery = query) }
    }

    fun reactToMessage(messageId: String, emoji: String) {
        viewModelScope.launch {
            chatRepository.toggleReaction(messageId, emoji)
        }
    }

    fun togglePin(message: MessageEntity) {
        viewModelScope.launch {
            chatRepository.togglePin(message.id, !message.isPinned)
        }
    }

    fun forwardMessage(message: MessageEntity) {
        viewModelScope.launch {
            chatRepository.sendStructuredMessage(
                kind = message.kind,
                body = message.body,
                isForwarded = true
            )
        }
    }

    fun deleteForMe(messageId: String) {
        viewModelScope.launch { chatRepository.deleteForMe(messageId) }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            val res = chatRepository.deleteForEveryone(messageId)
            if (res is AppResult.Error) {
                _composerState.update { it.copy(infoBanner = res.message) }
            }
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.retryFailedMessage(messageId)
        }
    }

    fun setDisappearingTimer(timer: DisappearingTimer) {
        viewModelScope.launch {
            chatRepository.setDisappearingTimer(timer.seconds)
            _composerState.update { it.copy(infoBanner = "Disappearing messages set to: ${timer.label}") }
        }
    }

    fun toggleMute(muted: Boolean) {
        viewModelScope.launch {
            chatRepository.toggleConversationMute(muted)
        }
    }

    fun loadSafetyNumber() {
        viewModelScope.launch {
            val self = uiState.value.selfUser ?: return@launch
            val partner = uiState.value.partnerUser ?: return@launch
            val fp = signalEngine.getSafetyNumber(self.id, partner.id)
            val verified = signalEngine.isPartnerIdentityVerified(partner.id)
            _composerState.update { it.copy(safetyNumber = fp, isIdentityVerified = verified) }
        }
    }

    fun togglePartnerVerified(verified: Boolean) {
        viewModelScope.launch {
            val partner = uiState.value.partnerUser ?: return@launch
            signalEngine.setPartnerIdentityVerified(partner.id, verified = verified)
            _composerState.update { it.copy(isIdentityVerified = verified) }
        }
    }

    fun trustUpdatedPartnerKeyAndResync() {
        viewModelScope.launch {
            val partner = uiState.value.partnerUser ?: return@launch
            chatRepository.trustNewPartnerIdentityAndResetSession(partner.id)
            loadSafetyNumber()
            _composerState.update { it.copy(infoBanner = "Partner key reset and session resynced.") }
        }
    }

    fun dismissBanner() {
        _composerState.update { it.copy(infoBanner = null) }
    }

    fun showInfoNotice(text: String) {
        _composerState.update { it.copy(infoBanner = text) }
    }

    fun createCameraCaptureUri(): Uri = mediaRepository.createCameraCaptureUri()

    fun getFileProviderUri(file: File): Uri = mediaRepository.getFileProviderUri(file)

    fun sendMediaUri(uri: Uri, category: MediaCategory) {
        val replyId = _composerState.value.replyingTo?.id
        _composerState.update { it.copy(replyingTo = null) }
        viewModelScope.launch {
            _composerState.update { it.copy(infoBanner = "Encrypting and uploading ${category.apiValue}…") }
            val res = chatRepository.sendEncryptedMediaMessage(
                sourceUri = uri,
                sourceFile = null,
                category = category,
                replyToMessageId = replyId
            )
            _composerState.update {
                it.copy(
                    infoBanner = if (res is AppResult.Error) res.message else null
                )
            }
        }
    }

    fun startVoiceRecording() {
        val ok = voiceRecorder.startRecording(locked = true)
        if (!ok) {
            _composerState.update { it.copy(infoBanner = "Unable to access microphone.") }
        }
    }

    fun cancelVoiceRecording() {
        voiceRecorder.cancelRecording()
    }

    fun stopAndSendVoiceRecording() {
        val recorded = voiceRecorder.stopAndFinalize() ?: return
        val replyId = _composerState.value.replyingTo?.id
        _composerState.update { it.copy(replyingTo = null) }
        viewModelScope.launch {
            val res = chatRepository.sendEncryptedMediaMessage(
                sourceUri = null,
                sourceFile = recorded.file,
                category = MediaCategory.VOICE,
                captionOrLabel = "Voice message",
                durationMs = recorded.durationMs,
                waveformCsv = recorded.waveformCsv,
                replyToMessageId = replyId
            )
            if (res is AppResult.Error) {
                _composerState.update { it.copy(infoBanner = res.message) }
            }
        }
    }

    fun downloadAttachment(attachment: AttachmentEntity, onReady: ((File) -> Unit)? = null) {
        viewModelScope.launch {
            val res = chatRepository.downloadAttachment(attachment)
            when (res) {
                is AppResult.Success -> onReady?.invoke(res.data)
                is AppResult.Error -> _composerState.update { it.copy(infoBanner = res.message) }
            }
        }
    }

    fun toggleVoicePlayback(attachment: AttachmentEntity) {
        val path = attachment.localPlainPath
        if (path != null && File(path).exists()) {
            voicePlayer.playOrToggle(attachment.id, path)
        } else {
            downloadAttachment(attachment) { downloaded ->
                voicePlayer.playOrToggle(attachment.id, downloaded.absolutePath)
            }
        }
    }

    fun seekVoice(attachmentId: String, fraction: Float) {
        voicePlayer.seekToFraction(attachmentId, fraction)
    }

    fun cycleVoiceSpeed() {
        voicePlayer.cyclePlaybackSpeed()
    }

    fun saveAttachmentToDownloads(attachment: AttachmentEntity) {
        viewModelScope.launch {
            val res = mediaRepository.saveToDownloads(attachment)
            _composerState.update {
                it.copy(infoBanner = when (res) {
                    is AppResult.Success -> res.data
                    is AppResult.Error -> res.message
                })
            }
        }
    }

    private data class CoreChatData(
        val self: UserProfile?,
        val partner: UserProfile?,
        val messages: List<MessageEntity>,
        val reactionsByMsg: Map<String, List<ReactionEntity>>,
        val pinnedMessage: MessageEntity?
    )

    private data class StatusChatData(
        val appearance: ChatAppearanceConfig,
        val disappearingSeconds: Long,
        val isMuted: Boolean,
        val online: Boolean,
        val typing: Boolean
    )
}
