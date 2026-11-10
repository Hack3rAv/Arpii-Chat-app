package com.systemutility.feature.calls

import com.systemutility.core.model.CallType
import kotlinx.serialization.Serializable

enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTED,
    RECONNECTING,
    ENDED
}

@Serializable
data class CallSignalingMessage(
    val type: String = "call_signal",
    val signalKind: String, // offer, answer, ice, ringing, reject, busy, hangup
    val callId: String,
    val callType: String = "audio", // audio, video
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

data class ActiveCallState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String? = null,
    val callType: CallType = CallType.AUDIO,
    val partnerUserId: String = "",
    val partnerName: String = "Paired Partner",
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val startedAtMs: Long = 0L,
    val connectedAtMs: Long = 0L,
    val elapsedSeconds: Long = 0L,
    val remoteVideoAvailable: Boolean = false,
    val iceStatusText: String = "",
    val endReason: String? = null
)

enum class CallRecordingStatus {
    IDLE,
    RECORDING,
    PAUSED,
    FINALIZING,
    ERROR
}

data class CallRecordingState(
    val status: CallRecordingStatus = CallRecordingStatus.IDLE,
    val callId: String? = null,
    val kind: String = "audio",
    val elapsedMs: Long = 0L,
    val errorMessage: String? = null
)
