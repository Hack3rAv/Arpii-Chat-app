package com.systemutility.feature.calls

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.systemutility.core.database.dao.CallDao
import com.systemutility.core.database.dao.UserDao
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.CallRecordingEntity
import com.systemutility.core.database.prefs.UserPreferencesDataStore
import com.systemutility.core.di.ApplicationScope
import com.systemutility.core.di.IoDispatcher
import com.systemutility.core.logging.SafeLogger
import com.systemutility.core.model.CallStatus
import com.systemutility.core.model.CallType
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.network.api.SystemUtilityApi
import com.systemutility.core.network.api.safeApiCall
import com.systemutility.core.network.dto.CallLogRequest
import com.systemutility.core.network.dto.TurnCredentialsResponse
import com.systemutility.core.network.ws.RealtimeWebSocketClient
import com.systemutility.core.result.AppResult
import com.systemutility.core.security.SecureSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcCallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: SystemUtilityApi,
    private val sessionStore: SecureSessionStore,
    private val userDao: UserDao,
    private val callDao: CallDao,
    private val prefsDataStore: UserPreferencesDataStore,
    private val wsClient: RealtimeWebSocketClient,
    val recordingManager: CallRecordingManager,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val observerStarted = AtomicBoolean(false)
    private val factoryInitialized = AtomicBoolean(false)

    private val _callState = MutableStateFlow(ActiveCallState())
    val callState: StateFlow<ActiveCallState> = _callState.asStateFlow()

    val recordingState: StateFlow<CallRecordingState> = recordingManager.state

    fun observeCallHistory(): Flow<List<CallHistoryEntity>> = callDao.observeCallHistory()
    fun observeRecordings(): Flow<List<CallRecordingEntity>> = callDao.observeRecordings()

    // Signaling state
    private var pendingIncomingOfferSdp: String? = null
    private val pendingRemoteIce = CopyOnWriteArrayList<IceCandidate>()
    private val cachedLocalIce = CopyOnWriteArrayList<CallSignalingMessage>()
    private var cachedLocalOfferSdp: String? = null
    private var remoteDescSet = false

    // WebRTC session objects
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    // Renderers
    private val localViewSinks = CopyOnWriteArrayList<VideoSink>()
    private val remoteViewSinks = CopyOnWriteArrayList<VideoSink>()

    private val localRelaySink = VideoSink { frame ->
        localViewSinks.forEach { it.onFrame(frame) }
        if (!_callState.value.remoteVideoAvailable) {
            recordingManager.onVideoFrame(frame)
        }
    }

    private val remoteRelaySink = VideoSink { frame ->
        if (!_callState.value.remoteVideoAvailable) {
            _callState.update { it.copy(remoteVideoAvailable = true) }
        }
        remoteViewSinks.forEach { it.onFrame(frame) }
        recordingManager.onVideoFrame(frame)
    }

    private val remoteAudioSink = AudioTrackSink { data: ByteBuffer, bits: Int, sampleRate: Int, channels: Int, _: Int, _: Long ->
        recordingManager.onRemotePcmFrame(data, bits, sampleRate, channels)
    }

    private var ringtone: Ringtone? = null
    private var ringTimeoutJob: Job? = null
    private var callTickerJob: Job? = null
    private var turnRefreshJob: Job? = null
    private var cachedTurnCredentials: TurnCredentialsResponse? = null
    private val iceRestartAttempted = AtomicBoolean(false)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    fun startSignalingObserver() {
        if (!FeatureFlags.CALLS_ENABLED) return
        if (!observerStarted.compareAndSet(false, true)) return

        appScope.launch(ioDispatcher) {
            wsClient.incomingEvents.collect { event ->
                val type = event["type"]?.jsonPrimitive?.content ?: return@collect
                if (type == "presence") {
                    val online = event["online"]?.jsonPrimitive?.content == "true"
                    if (online && _callState.value.phase == CallPhase.OUTGOING_RINGING) {
                        replayPendingOfferIfOutgoing()
                    }
                    return@collect
                }
                if (type != "call_signal") return@collect

                val signalKind = event["signalKind"]?.jsonPrimitive?.content ?: return@collect
                val callId = event["callId"]?.jsonPrimitive?.content ?: ""
                val callTypeStr = event["callType"]?.jsonPrimitive?.content ?: "audio"
                val sdp = event["sdp"]?.jsonPrimitive?.content
                val candidate = event["candidate"]?.jsonPrimitive?.content
                val sdpMid = event["sdpMid"]?.jsonPrimitive?.content
                val sdpMLineIndex = event["sdpMLineIndex"]?.jsonPrimitive?.intOrNull

                handleIncomingSignal(
                    CallSignalingMessage(
                        signalKind = signalKind,
                        callId = callId,
                        callType = callTypeStr,
                        sdp = sdp,
                        candidate = candidate,
                        sdpMid = sdpMid,
                        sdpMLineIndex = sdpMLineIndex
                    )
                )
            }
        }
    }

    fun getEglContext(): EglBase.Context? {
        if (!FeatureFlags.CALLS_ENABLED) return null
        return ensureEglBase().eglBaseContext
    }

    private fun ensureEglBase(): EglBase {
        return eglBase ?: EglBase.create().also { eglBase = it }
    }

    fun attachLocalSink(sink: VideoSink) {
        if (!localViewSinks.contains(sink)) {
            localViewSinks.add(sink)
        }
    }

    fun detachLocalSink(sink: VideoSink) {
        localViewSinks.remove(sink)
    }

    fun attachRemoteSink(sink: VideoSink) {
        if (!remoteViewSinks.contains(sink)) {
            remoteViewSinks.add(sink)
        }
    }

    fun detachRemoteSink(sink: VideoSink) {
        remoteViewSinks.remove(sink)
    }

    fun notifyWakeReadyProbe() {
        if (!FeatureFlags.CALLS_ENABLED) return
        if (_callState.value.phase == CallPhase.IDLE) {
            wsClient.connect()
            sendSignal(
                CallSignalingMessage(
                    signalKind = "ready",
                    callId = "wake_probe",
                    callType = "audio"
                )
            )
        }
    }

    fun startCall(type: CallType) {
        if (!FeatureFlags.CALLS_ENABLED) return
        startSignalingObserver()
        if (_callState.value.phase != CallPhase.IDLE) return

        appScope.launch(ioDispatcher) {
            val self = sessionStore.getSession() ?: return@launch
            val partner = userDao.getPartnerUser() ?: return@launch

            val callId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val isVideo = type == CallType.VIDEO

            cachedLocalIce.clear()
            pendingRemoteIce.clear()
            cachedLocalOfferSdp = null
            remoteDescSet = false

            _callState.value = ActiveCallState(
                phase = CallPhase.OUTGOING_RINGING,
                callId = callId,
                callType = type,
                partnerUserId = partner.id,
                partnerName = partner.displayName,
                isMuted = false,
                isSpeakerOn = isVideo,
                isCameraEnabled = isVideo,
                isFrontCamera = true,
                startedAtMs = now,
                iceStatusText = "Connecting…"
            )

            callDao.upsertCall(
                CallHistoryEntity(
                    id = callId,
                    callerUserId = self.userId,
                    calleeUserId = partner.id,
                    callType = type.apiValue,
                    status = CallStatus.INITIATED.name,
                    startedAt = now,
                    isLocalInitiated = true
                )
            )
            pushBackendCallLog(callId, partner.id, type.apiValue, CallStatus.INITIATED.name, now, null, null)

            startCallTimeout(callId, isCaller = true)
            startOrUpdateCallService(partner.displayName, "Calling…", isVideo, false)

            val pc = setupWebRtcSession(isVideo) ?: run {
                endCallInternal("Unable to initialize WebRTC media engine", CallStatus.FAILED, sendHangup = false)
                return@launch
            }

            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            cachedLocalOfferSdp = desc.description
                            sendSignal(
                                CallSignalingMessage(
                                    signalKind = "offer",
                                    callId = callId,
                                    callType = type.apiValue,
                                    sdp = desc.description
                                )
                            )
                        }
                    }, desc)
                }

                override fun onCreateFailure(err: String?) {
                    endCallInternal("Failed creating call offer", CallStatus.FAILED, sendHangup = false)
                }
            }, buildMediaConstraints(isVideo))
        }
    }

    fun acceptCall() {
        if (!FeatureFlags.CALLS_ENABLED) return
        val current = _callState.value
        if (current.phase != CallPhase.INCOMING_RINGING) return
        val callId = current.callId ?: return
        val offerSdp = pendingIncomingOfferSdp ?: return

        stopRingtone()
        cancelIncomingNotification()
        ringTimeoutJob?.cancel()

        appScope.launch(ioDispatcher) {
            val now = System.currentTimeMillis()
            val isVideo = current.callType == CallType.VIDEO
            _callState.update {
                it.copy(
                    phase = CallPhase.CONNECTED,
                    connectedAtMs = now,
                    isSpeakerOn = isVideo,
                    iceStatusText = "Negotiating ICE…"
                )
            }
            callDao.updateCallState(callId, CallStatus.ANSWERED.name, answeredAt = now)
            pushBackendCallLog(callId, current.partnerUserId, current.callType.apiValue, CallStatus.ANSWERED.name, current.startedAtMs, now, null)

            startOrUpdateCallService(current.partnerName, "Encrypted Call", isVideo, false)
            startElapsedTicker()

            val pc = setupWebRtcSession(isVideo) ?: run {
                endCallInternal("Unable to initialize call media", CallStatus.FAILED, sendHangup = true)
                return@launch
            }

            pc.setRemoteDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    remoteDescSet = true
                    flushPendingRemoteIce()
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(answerDesc: SessionDescription) {
                            pc.setLocalDescription(object : SimpleSdpObserver() {
                                override fun onSetSuccess() {
                                    sendSignal(
                                        CallSignalingMessage(
                                            signalKind = "answer",
                                            callId = callId,
                                            callType = current.callType.apiValue,
                                            sdp = answerDesc.description
                                        )
                                    )
                                }
                            }, answerDesc)
                        }

                        override fun onCreateFailure(err: String?) {
                            endCallInternal("Failed creating call answer", CallStatus.FAILED, sendHangup = true)
                        }
                    }, buildMediaConstraints(isVideo))
                }

                override fun onSetFailure(err: String?) {
                    endCallInternal("Invalid remote SDP offer", CallStatus.FAILED, sendHangup = true)
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        }
    }

    fun rejectCall() {
        val current = _callState.value
        if (current.phase != CallPhase.INCOMING_RINGING) return
        val callId = current.callId ?: return
        stopRingtone()
        cancelIncomingNotification()
        sendSignal(
            CallSignalingMessage(
                signalKind = "reject",
                callId = callId,
                callType = current.callType.apiValue
            )
        )
        endCallInternal("Declined", CallStatus.REJECTED, sendHangup = false)
    }

    fun hangup(reason: String = "Call ended") {
        val current = _callState.value
        if (current.phase == CallPhase.IDLE) return
        val status = if (current.connectedAtMs > 0L) CallStatus.ENDED else CallStatus.MISSED
        endCallInternal(reason, status, sendHangup = true)
    }

    fun toggleMute() {
        val next = !_callState.value.isMuted
        localAudioTrack?.setEnabled(!next)
        audioDeviceModule?.setMicrophoneMute(next)
        _callState.update { it.copy(isMuted = next) }
    }

    fun toggleSpeaker() {
        val next = !_callState.value.isSpeakerOn
        applySpeakerRouting(next)
        _callState.update { it.copy(isSpeakerOn = next) }
    }

    fun toggleCamera() {
        val current = _callState.value
        if (current.callType != CallType.VIDEO) return
        val next = !current.isCameraEnabled
        localVideoTrack?.setEnabled(next)
        _callState.update { it.copy(isCameraEnabled = next) }
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _callState.update { it.copy(isFrontCamera = isFrontCamera) }
            }

            override fun onCameraSwitchError(err: String?) {}
        })
    }

    // Strict Manual Local-Only Call Recording Controls
    fun startManualRecording() {
        val current = _callState.value
        val callId = current.callId ?: return
        if (current.phase != CallPhase.CONNECTED && current.phase != CallPhase.RECONNECTING) return

        val wantVideo = current.callType == CallType.VIDEO
        val ok = recordingManager.startManualRecording(
            callId = callId,
            includeVideo = wantVideo,
            sharedEglContext = if (wantVideo) getEglContext() else null
        )
        if (ok) {
            startOrUpdateCallService(current.partnerName, "Encrypted Call", wantVideo, isRecording = true)
        }
    }

    fun pauseRecording() {
        recordingManager.pauseRecording()
    }

    fun resumeRecording() {
        recordingManager.resumeRecording()
    }

    fun stopRecording() {
        val current = _callState.value
        recordingManager.stopAndSaveRecording {
            if (_callState.value.phase == CallPhase.CONNECTED || _callState.value.phase == CallPhase.RECONNECTING) {
                startOrUpdateCallService(
                    current.partnerName,
                    "Encrypted Call",
                    current.callType == CallType.VIDEO,
                    isRecording = false
                )
            }
        }
    }

    private suspend fun handleIncomingSignal(msg: CallSignalingMessage) {
        val current = _callState.value
        when (msg.signalKind) {
            "ready" -> {
                if (current.phase == CallPhase.OUTGOING_RINGING) {
                    replayPendingOfferIfOutgoing()
                }
            }

            "offer" -> {
                if (current.phase != CallPhase.IDLE && current.callId != msg.callId) {
                    sendSignal(msg.copy(signalKind = "busy", sdp = null))
                    return
                }
                if (current.callId == msg.callId && current.phase != CallPhase.IDLE) {
                    return // duplicate replayed offer
                }

                val self = sessionStore.getSession() ?: return
                val partner = userDao.getPartnerUser()
                val partnerId = partner?.id ?: return
                val partnerName = partner.displayName
                val cType = if (msg.callType == "video") CallType.VIDEO else CallType.AUDIO
                val now = System.currentTimeMillis()

                pendingIncomingOfferSdp = msg.sdp
                pendingRemoteIce.clear()
                remoteDescSet = false

                _callState.value = ActiveCallState(
                    phase = CallPhase.INCOMING_RINGING,
                    callId = msg.callId,
                    callType = cType,
                    partnerUserId = partnerId,
                    partnerName = partnerName,
                    isCameraEnabled = cType == CallType.VIDEO,
                    startedAtMs = now
                )

                callDao.upsertCall(
                    CallHistoryEntity(
                        id = msg.callId,
                        callerUserId = partnerId,
                        calleeUserId = self.userId,
                        callType = cType.apiValue,
                        status = CallStatus.RINGING.name,
                        startedAt = now,
                        isLocalInitiated = false
                    )
                )

                sendSignal(msg.copy(signalKind = "ringing", sdp = null))
                startIncomingRingtone()
                showIncomingCallNotification(partnerName, cType == CallType.VIDEO)
                startCallTimeout(msg.callId, isCaller = false)
            }

            "ringing" -> {
                if (current.callId == msg.callId && current.phase == CallPhase.OUTGOING_RINGING) {
                    _callState.update { it.copy(iceStatusText = "Ringing…") }
                }
            }

            "answer" -> {
                if (current.callId != msg.callId) return
                val pc = peerConnection ?: return
                val answerSdp = msg.sdp ?: return
                ringTimeoutJob?.cancel()
                val now = System.currentTimeMillis()
                _callState.update {
                    it.copy(
                        phase = CallPhase.CONNECTED,
                        connectedAtMs = now,
                        iceStatusText = "Connected"
                    )
                }
                callDao.updateCallState(msg.callId, CallStatus.ANSWERED.name, answeredAt = now)
                startElapsedTicker()
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        flushPendingRemoteIce()
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
            }

            "ice" -> {
                if (current.callId != msg.callId) return
                val candStr = msg.candidate ?: return
                val mid = msg.sdpMid ?: return
                val idx = msg.sdpMLineIndex ?: 0
                val ice = IceCandidate(mid, idx, candStr)
                if (remoteDescSet && peerConnection != null) {
                    peerConnection?.addIceCandidate(ice)
                } else {
                    pendingRemoteIce.add(ice)
                }
            }

            "reject" -> {
                if (current.callId == msg.callId) {
                    endCallInternal("Call declined", CallStatus.REJECTED, sendHangup = false)
                }
            }

            "busy" -> {
                if (current.callId == msg.callId) {
                    endCallInternal("Partner is in another call", CallStatus.REJECTED, sendHangup = false)
                }
            }

            "hangup" -> {
                if (current.callId == msg.callId) {
                    val status = if (current.connectedAtMs > 0L) CallStatus.ENDED else CallStatus.MISSED
                    endCallInternal("Partner ended call", status, sendHangup = false)
                }
            }
        }
    }

    private fun replayPendingOfferIfOutgoing() {
        val current = _callState.value
        val callId = current.callId ?: return
        val offerSdp = cachedLocalOfferSdp ?: return
        sendSignal(
            CallSignalingMessage(
                signalKind = "offer",
                callId = callId,
                callType = current.callType.apiValue,
                sdp = offerSdp
            )
        )
        cachedLocalIce.forEach { sendSignal(it) }
    }

    private suspend fun setupWebRtcSession(isVideo: Boolean): PeerConnection? {
        if (!factoryInitialized.getAndSet(true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
        }

        val egl = ensureEglBase()
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setSamplesReadyCallback { samples ->
                recordingManager.onLocalPcmFrame(
                    samples.data,
                    samples.sampleRate,
                    samples.channelCount
                )
            }
            .createAudioDeviceModule()
        audioDeviceModule = adm

        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        peerConnectionFactory = factory

        iceRestartAttempted.set(false)
        cachedTurnCredentials = null
        val iceOutcome = fetchIceServers()
        if (!iceOutcome.usedTurn && iceOutcome.warning != null) {
            _callState.update { it.copy(iceStatusText = iceOutcome.warning) }
        }
        val rtcConfig = buildRtcConfiguration(iceOutcome.servers)

        val callId = _callState.value.callId ?: ""
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceRestartAttempted.set(false)
                        _callState.update {
                            it.copy(
                                phase = if (it.phase == CallPhase.RECONNECTING) CallPhase.CONNECTED else it.phase,
                                iceStatusText = "Encrypted DTLS-SRTP"
                            )
                        }
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _callState.update { it.copy(phase = CallPhase.RECONNECTING, iceStatusText = "Reconnecting…") }
                        appScope.launch(ioDispatcher) {
                            val exp = cachedTurnCredentials?.expiresAt ?: 0L
                            if (exp <= System.currentTimeMillis() + 60_000L) {
                                refreshPeerIceConfiguration(triggerIceRestart = true)
                            } else {
                                runCatching { peerConnection?.restartIce() }
                            }
                        }
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        if (iceRestartAttempted.compareAndSet(false, true)) {
                            _callState.update {
                                it.copy(phase = CallPhase.RECONNECTING, iceStatusText = "Refreshing TURN relay & retrying…")
                            }
                            appScope.launch(ioDispatcher) {
                                refreshPeerIceConfiguration(triggerIceRestart = true)
                            }
                        } else {
                            _callState.update { it.copy(iceStatusText = "Network connection failed") }
                            appScope.launch {
                                endCallInternal("Connection lost", CallStatus.FAILED, sendHangup = true)
                            }
                        }
                    }

                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val sig = CallSignalingMessage(
                    signalKind = "ice",
                    callId = callId,
                    callType = _callState.value.callType.apiValue,
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex
                )
                cachedLocalIce.add(sig)
                sendSignal(sig)
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    track.addSink(remoteRelaySink)
                    _callState.update { it.copy(remoteVideoAvailable = true) }
                } else if (track is AudioTrack) {
                    remoteAudioTrack = track
                    runCatching { track.addSink(remoteAudioSink) }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
        }) ?: return null

        peerConnection = pc
        iceOutcome.expiresAtMs?.let { scheduleTurnCredentialRefresh(it) }
        configureCallAudioMode(isSpeaker = isVideo)

        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource).apply { setEnabled(true) }
        localAudioSource = audioSource
        localAudioTrack = audioTrack
        pc.addTrack(audioTrack, listOf("ARDAMS"))

        if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            createCameraCapturer()?.let { capturer ->
                videoCapturer = capturer
                val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
                surfaceTextureHelper = helper
                val vSource = factory.createVideoSource(capturer.isScreencast)
                capturer.initialize(helper, context, vSource.capturerObserver)
                runCatching { capturer.startCapture(720, 1280, 24) }
                val vTrack = factory.createVideoTrack("ARDAMSv0", vSource).apply { setEnabled(true) }
                vTrack.addSink(localRelaySink)
                localVideoSource = vSource
                localVideoTrack = vTrack
                pc.addTrack(vTrack, listOf("ARDAMS"))
            }
        }

        return pc
    }

    private data class IceFetchOutcome(
        val servers: List<PeerConnection.IceServer>,
        val expiresAtMs: Long?,
        val usedTurn: Boolean,
        val warning: String?
    )

    private fun buildRtcConfiguration(iceServers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun scheduleTurnCredentialRefresh(expiresAtMs: Long) {
        turnRefreshJob?.cancel()
        turnRefreshJob = appScope.launch(ioDispatcher) {
            val now = System.currentTimeMillis()
            val delayMs = (expiresAtMs - now - 60_000L).coerceAtLeast(30_000L)
            delay(delayMs)
            val currentPhase = _callState.value.phase
            if (currentPhase == CallPhase.IDLE || currentPhase == CallPhase.ENDED) return@launch
            refreshPeerIceConfiguration(triggerIceRestart = false)
        }
    }

    private suspend fun refreshPeerIceConfiguration(triggerIceRestart: Boolean) {
        val pc = peerConnection ?: return
        val outcome = fetchIceServers()
        runCatching { pc.setConfiguration(buildRtcConfiguration(outcome.servers)) }
            .onFailure { SafeLogger.w("WebRTC", "Failed applying refreshed ICE config: ${it.message}") }
        outcome.expiresAtMs?.let { scheduleTurnCredentialRefresh(it) }
        if (!outcome.usedTurn && outcome.warning != null) {
            _callState.update { it.copy(iceStatusText = outcome.warning) }
        }
        if (triggerIceRestart) {
            runCatching { pc.restartIce() }
        }
    }

    private suspend fun fetchIceServers(): IceFetchOutcome {
        val servers = ArrayList<PeerConnection.IceServer>()
        return when (val res = safeApiCall { api.getTurnCredentials() }) {
            is AppResult.Success -> {
                val dto = res.data
                cachedTurnCredentials = dto
                dto.iceServers.forEach { entry ->
                    if (entry.urls.isNotEmpty()) {
                        val builder = PeerConnection.IceServer.builder(entry.urls)
                        if (!entry.username.isNullOrBlank() && !entry.credential.isNullOrBlank()) {
                            builder.setUsername(entry.username).setPassword(entry.credential)
                        }
                        servers.add(builder.createIceServer())
                    }
                }
                if (servers.isNotEmpty()) {
                    val hasTurn = dto.iceServers.any { entry ->
                        entry.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
                    }
                    IceFetchOutcome(
                        servers = servers,
                        expiresAtMs = dto.expiresAt.takeIf { it > 0L },
                        usedTurn = hasTurn,
                        warning = if (hasTurn) null else "STUN-only ICE config received"
                    )
                } else {
                    IceFetchOutcome(
                        servers = fallbackStunServers(),
                        expiresAtMs = null,
                        usedTurn = false,
                        warning = "Empty ICE server list; using Cloudflare STUN fallback"
                    )
                }
            }
            is AppResult.Error -> {
                SafeLogger.w("WebRTC", "Cloudflare TURN credential request failed (${res.code}): ${res.message}")
                IceFetchOutcome(
                    servers = fallbackStunServers(),
                    expiresAtMs = null,
                    usedTurn = false,
                    warning = if (res.code == "TURN_NOT_CONFIGURED") {
                        "STUN-only mode (TURN secrets not set)"
                    } else {
                        "TURN unavailable; using STUN fallback"
                    }
                )
            }
        }
    }

    private fun fallbackStunServers(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
    )

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val devices = enumerator.deviceNames
        val front = devices.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: devices.firstOrNull() ?: return null
        return enumerator.createCapturer(chosen, null)
    }

    private fun flushPendingRemoteIce() {
        val pc = peerConnection ?: return
        pendingRemoteIce.forEach { pc.addIceCandidate(it) }
        pendingRemoteIce.clear()
    }

    private fun buildMediaConstraints(isVideo: Boolean): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (isVideo) "true" else "false"))
        }
    }

    private fun sendSignal(msg: CallSignalingMessage) {
        wsClient.sendJson(json.encodeToString(msg))
    }

    private fun endCallInternal(reason: String, finalStatus: CallStatus, sendHangup: Boolean) {
        ringTimeoutJob?.cancel()
        callTickerJob?.cancel()
        turnRefreshJob?.cancel()
        turnRefreshJob = null
        cachedTurnCredentials = null
        iceRestartAttempted.set(false)
        stopRingtone()
        cancelIncomingNotification()

        val current = _callState.value
        val callId = current.callId
        val now = System.currentTimeMillis()

        if (sendHangup && callId != null) {
            sendSignal(
                CallSignalingMessage(
                    signalKind = "hangup",
                    callId = callId,
                    callType = current.callType.apiValue
                )
            )
        }

        recordingManager.abortIfRunning()

        // Teardown WebRTC resources safely
        runCatching { remoteVideoTrack?.removeSink(remoteRelaySink) }
        runCatching { remoteAudioTrack?.removeSink(remoteAudioSink) }
        runCatching { localVideoTrack?.removeSink(localRelaySink) }
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { localAudioSource?.dispose() }
        runCatching { localVideoSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        runCatching { peerConnectionFactory?.dispose() }
        runCatching { audioDeviceModule?.release() }

        remoteVideoTrack = null
        remoteAudioTrack = null
        localVideoTrack = null
        localAudioTrack = null
        videoCapturer = null
        surfaceTextureHelper = null
        localAudioSource = null
        localVideoSource = null
        peerConnection = null
        peerConnectionFactory = null
        audioDeviceModule = null
        pendingIncomingOfferSdp = null
        cachedLocalOfferSdp = null
        pendingRemoteIce.clear()
        cachedLocalIce.clear()
        remoteDescSet = false

        restoreAudioMode()
        stopCallForegroundService()

        if (callId != null && current.partnerUserId.isNotBlank()) {
            appScope.launch(ioDispatcher) {
                callDao.updateCallState(callId, finalStatus.name, endedAt = now)
                pushBackendCallLog(
                    callId = callId,
                    calleeUserId = current.partnerUserId,
                    callType = current.callType.apiValue,
                    status = finalStatus.name,
                    startedAt = current.startedAtMs.coerceAtLeast(now - 1000L),
                    answeredAt = current.connectedAtMs.takeIf { it > 0L },
                    endedAt = now
                )
            }
        }

        _callState.update {
            it.copy(
                phase = CallPhase.ENDED,
                endReason = reason
            )
        }

        appScope.launch {
            delay(1_400L)
            if (_callState.value.phase == CallPhase.ENDED) {
                _callState.value = ActiveCallState(phase = CallPhase.IDLE)
            }
        }
    }

    private fun startElapsedTicker() {
        callTickerJob?.cancel()
        callTickerJob = appScope.launch {
            while (isActive) {
                delay(1_000L)
                val st = _callState.value
                if (st.phase == CallPhase.CONNECTED || st.phase == CallPhase.RECONNECTING) {
                    val base = st.connectedAtMs
                    if (base > 0L) {
                        _callState.update { it.copy(elapsedSeconds = ((System.currentTimeMillis() - base) / 1000L).coerceAtLeast(0L)) }
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun startCallTimeout(callId: String, isCaller: Boolean) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = appScope.launch {
            delay(45_000L)
            val st = _callState.value
            if (st.callId == callId && (st.phase == CallPhase.OUTGOING_RINGING || st.phase == CallPhase.INCOMING_RINGING)) {
                endCallInternal(
                    reason = if (isCaller) "No answer" else "Missed call",
                    finalStatus = CallStatus.MISSED,
                    sendHangup = isCaller
                )
            }
        }
    }

    private fun configureCallAudioMode(isSpeaker: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeakerRouting(isSpeaker)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun applySpeakerRouting(speakerOn: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = speakerOn
    }

    private fun restoreAudioMode() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = previousAudioMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null
    }

    private fun startIncomingRingtone() {
        appScope.launch(ioDispatcher) {
            val prefs = prefsDataStore.notificationFlow.first()
            if (prefs.dndEnabled || !prefs.callRingtoneEnabled) return@launch
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val rt = RingtoneManager.getRingtone(context, uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rt.isLooping = true
                }
                rt.play()
                ringtone = rt
            }
            if (prefs.vibrateEnabled) {
                triggerIncomingVibration()
            }
        }
    }

    private fun triggerIncomingVibration() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 700), 0))
        }
    }

    private fun stopRingtone() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vib.cancel()
        }
    }

    private fun showIncomingCallNotification(partnerName: String, isVideo: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return

        val pending = PendingIntent.getActivity(
            context,
            7102,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val kindLabel = if (isVideo) "Incoming Encrypted Video Call" else "Incoming Encrypted Voice Call"
        val notification = NotificationCompat.Builder(context, "sysutil_calls")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(partnerName)
            .setContentText(kindLabel)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(INCOMING_NOTIFICATION_ID, notification)
    }

    private fun cancelIncomingNotification() {
        NotificationManagerCompat.from(context).cancel(INCOMING_NOTIFICATION_ID)
    }

    private fun startOrUpdateCallService(
        partnerName: String,
        subtitle: String,
        isVideo: Boolean,
        isRecording: Boolean
    ) {
        val intent = Intent(context, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_START_OR_UPDATE
            putExtra(CallForegroundService.EXTRA_TITLE, partnerName)
            putExtra(CallForegroundService.EXTRA_SUBTITLE, subtitle)
            putExtra(CallForegroundService.EXTRA_IS_VIDEO, isVideo)
            putExtra(CallForegroundService.EXTRA_IS_RECORDING, isRecording)
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            SafeLogger.w("WebRTC", "Unable to start call service: ${it.message}")
        }
    }

    private fun stopCallForegroundService() {
        val intent = Intent(context, CallForegroundService::class.java).apply {
            action = CallForegroundService.ACTION_STOP
        }
        runCatching { context.startService(intent) }
    }

    private suspend fun pushBackendCallLog(
        callId: String,
        calleeUserId: String,
        callType: String,
        status: String,
        startedAt: Long,
        answeredAt: Long?,
        endedAt: Long?
    ) {
        safeApiCall {
            api.recordCallLog(
                CallLogRequest(
                    id = callId,
                    calleeUserId = calleeUserId,
                    callType = callType,
                    status = status,
                    startedAt = startedAt,
                    answeredAt = answeredAt,
                    endedAt = endedAt
                )
            )
        }
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {}
        override fun onSetFailure(err: String?) {}
    }

    companion object {
        private const val INCOMING_NOTIFICATION_ID = 7102
    }
}
