package com.systemutility.feature.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.CallRecordingEntity
import com.systemutility.core.model.CallType
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoSink
import java.io.File
import javax.inject.Inject

data class CallHistoryUiState(
    val selfUser: UserProfile? = null,
    val partnerUser: UserProfile? = null,
    val callHistory: List<CallHistoryEntity> = emptyList(),
    val recordings: List<CallRecordingEntity> = emptyList(),
    val activePlaybackRecording: CallRecordingEntity? = null,
    val activePlaybackFile: File? = null,
    val statusNotice: String? = null
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: WebRtcCallManager,
    userDao: UserDao
) : ViewModel() {

    val activeCallState: StateFlow<ActiveCallState> = callManager.callState
    val recordingState: StateFlow<CallRecordingState> = callManager.recordingState

    init {
        if (FeatureFlags.CALLS_ENABLED) {
            callManager.startSignalingObserver()
        }
    }

    private val _playbackState = MutableStateFlow<Pair<CallRecordingEntity?, File?>>(null to null)
    private val _statusNotice = MutableStateFlow<String?>(null)

    val historyUiState: StateFlow<CallHistoryUiState> = combine(
        userDao.observeSelfUser(),
        userDao.observePartnerUser(),
        callManager.observeCallHistory(),
        callManager.observeRecordings(),
        combine(_playbackState, _statusNotice) { pb, notice -> pb to notice }
    ) { self, partner, history, recs, extras ->
        val (pb, notice) = extras
        CallHistoryUiState(
            selfUser = self?.toDomain(),
            partnerUser = partner?.toDomain(),
            callHistory = history,
            recordings = recs,
            activePlaybackRecording = pb.first,
            activePlaybackFile = pb.second,
            statusNotice = notice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CallHistoryUiState()
    )

    fun startVoiceCall() = callManager.startCall(CallType.AUDIO)
    fun startVideoCall() = callManager.startCall(CallType.VIDEO)
    fun acceptCall() = callManager.acceptCall()
    fun rejectCall() = callManager.rejectCall()
    fun hangup() = callManager.hangup()
    fun toggleMute() = callManager.toggleMute()
    fun toggleSpeaker() = callManager.toggleSpeaker()
    fun toggleCamera() = callManager.toggleCamera()
    fun switchCamera() = callManager.switchCamera()

    fun startManualRecording() = callManager.startManualRecording()
    fun pauseRecording() = callManager.pauseRecording()
    fun resumeRecording() = callManager.resumeRecording()
    fun stopRecording() = callManager.stopRecording()

    fun getEglContext(): EglBase.Context? = callManager.getEglContext()
    fun attachLocalSink(sink: VideoSink) = callManager.attachLocalSink(sink)
    fun detachLocalSink(sink: VideoSink) = callManager.detachLocalSink(sink)
    fun attachRemoteSink(sink: VideoSink) = callManager.attachRemoteSink(sink)
    fun detachRemoteSink(sink: VideoSink) = callManager.detachRemoteSink(sink)

    fun openRecordingPlayback(recording: CallRecordingEntity) {
        viewModelScope.launch {
            val file = callManager.recordingManager.decryptRecordingToTemp(recording)
            if (file != null) {
                _playbackState.value = recording to file
            } else {
                _statusNotice.value = "Failed decrypting recording file."
            }
        }
    }

    fun closeRecordingPlayback() {
        _playbackState.value = null to null
        callManager.recordingManager.clearPlaybackTemp()
    }

    fun deleteRecording(recording: CallRecordingEntity) {
        viewModelScope.launch {
            if (_playbackState.value.first?.id == recording.id) {
                closeRecordingPlayback()
            }
            callManager.recordingManager.deleteRecording(recording)
            _statusNotice.value = "Encrypted recording deleted."
        }
    }

    fun clearNotice() {
        _statusNotice.value = null
    }
}
