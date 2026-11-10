package com.systemutility.feature.calls.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.systemutility.core.model.CallType
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.calls.ActiveCallState
import com.systemutility.feature.calls.CallPhase
import com.systemutility.feature.calls.CallRecordingState
import com.systemutility.feature.calls.CallRecordingStatus
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink

@Composable
fun ActiveCallOverlayScreen(
    callState: ActiveCallState,
    recordingState: CallRecordingState,
    eglContext: EglBase.Context?,
    onAttachLocalSink: (VideoSink) -> Unit,
    onDetachLocalSink: (VideoSink) -> Unit,
    onAttachRemoteSink: (VideoSink) -> Unit,
    onDetachRemoteSink: (VideoSink) -> Unit,
    onAcceptCall: () -> Unit,
    onRejectCall: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onStartRecording: () -> Unit,
    onPauseRecording: () -> Unit,
    onResumeRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    val context = LocalContext.current
    val acceptPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            onAcceptCall()
        }
    }

    val isVideoCall = callState.callType == CallType.VIDEO
    val isLiveSession = callState.phase == CallPhase.CONNECTED ||
        callState.phase == CallPhase.RECONNECTING ||
        callState.phase == CallPhase.OUTGOING_RINGING

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A141A), Color(0xFF102027), Color(0xFF0A0F14))
                )
            )
    ) {
        // Remote Full-Screen Video Renderer
        if (isVideoCall && isLiveSession && eglContext != null) {
            WebRtcSurfaceView(
                modifier = Modifier.fillMaxSize(),
                eglContext = eglContext,
                mirror = false,
                onAttach = onAttachRemoteSink,
                onDetach = onDetachRemoteSink
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Header & Recording HUD
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.42f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isVideoCall) "E2EE Video Call" else "E2EE Voice Call",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Unmistakable Local Manual Recording Indicator
                if (recordingState.status == CallRecordingStatus.RECORDING ||
                    recordingState.status == CallRecordingStatus.PAUSED
                ) {
                    Spacer(modifier = Modifier.height(10.dp))
                    RecordingActiveHudPill(
                        state = recordingState,
                        onPauseOrResume = {
                            if (recordingState.status == CallRecordingStatus.RECORDING) onPauseRecording() else onResumeRecording()
                        },
                        onStop = onStopRecording
                    )
                } else if (recordingState.status == CallRecordingStatus.FINALIZING) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = "Encrypting recording to local vault…",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // Center Identity / Call Status
            if (!isVideoCall || !callState.remoteVideoAvailable || callState.phase == CallPhase.INCOMING_RINGING) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = callState.partnerName.firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = callState.partnerName,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val statusLine = when (callState.phase) {
                        CallPhase.INCOMING_RINGING ->
                            if (isVideoCall) "Incoming encrypted video call…" else "Incoming encrypted voice call…"
                        CallPhase.OUTGOING_RINGING -> callState.iceStatusText.ifBlank { "Calling…" }
                        CallPhase.RECONNECTING -> "Reconnecting…"
                        CallPhase.CONNECTED -> formatCallDuration(callState.elapsedSeconds)
                        CallPhase.ENDED -> callState.endReason ?: "Call ended"
                        CallPhase.IDLE -> ""
                    }
                    Text(
                        text = statusLine,
                        color = if (callState.phase == CallPhase.RECONNECTING) Color(0xFFFBBF24) else Color.White.copy(alpha = 0.78f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                // During active video, keep compact timer overlay near center-top
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = "${callState.partnerName} • ${formatCallDuration(callState.elapsedSeconds)}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            // Bottom Control Deck
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Local PIP Preview during Video Call
                if (isVideoCall && callState.isCameraEnabled && isLiveSession && eglContext != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(width = 108.dp, height = 152.dp)
                                .border(1.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        ) {
                            WebRtcSurfaceView(
                                modifier = Modifier.fillMaxSize(),
                                eglContext = eglContext,
                                mirror = callState.isFrontCamera,
                                isZOrderMediaOverlay = true,
                                onAttach = onAttachLocalSink,
                                onDetach = onDetachLocalSink
                            )
                        }
                    }
                }

                when (callState.phase) {
                    CallPhase.INCOMING_RINGING -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 18.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFDC2626),
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    IconButton(onClick = onRejectCall) {
                                        Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Decline", color = Color.White, fontSize = 13.sp)
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF16A34A),
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val needCam = isVideoCall && ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.CAMERA
                                            ) != PackageManager.PERMISSION_GRANTED
                                            val needMic = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) != PackageManager.PERMISSION_GRANTED
                                            if (needMic || needCam) {
                                                val perms = buildList {
                                                    add(Manifest.permission.RECORD_AUDIO)
                                                    if (isVideoCall) add(Manifest.permission.CAMERA)
                                                }.toTypedArray()
                                                acceptPermLauncher.launch(perms)
                                            } else {
                                                onAcceptCall()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(30.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Accept", color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }

                    CallPhase.ENDED, CallPhase.IDLE -> Unit

                    else -> {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = Color(0xFF162127).copy(alpha = 0.90f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    CallActionButton(
                                        icon = if (callState.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                        label = if (callState.isMuted) "Unmute" else "Mute",
                                        active = callState.isMuted,
                                        onClick = onToggleMute
                                    )
                                    CallActionButton(
                                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                                        label = "Speaker",
                                        active = callState.isSpeakerOn,
                                        onClick = onToggleSpeaker
                                    )
                                    if (isVideoCall) {
                                        CallActionButton(
                                            icon = if (callState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                            label = if (callState.isCameraEnabled) "Camera" else "Cam Off",
                                            active = !callState.isCameraEnabled,
                                            onClick = onToggleCamera
                                        )
                                        CallActionButton(
                                            icon = Icons.Default.Cameraswitch,
                                            label = "Flip",
                                            active = false,
                                            onClick = onSwitchCamera
                                        )
                                    }
                                    // Manual Explicit Recording Toggle (Connected calls only)
                                    val isRec = recordingState.status == CallRecordingStatus.RECORDING ||
                                        recordingState.status == CallRecordingStatus.PAUSED
                                    CallActionButton(
                                        icon = if (isRec) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                        label = if (isRec) "Stop Rec" else "Record",
                                        active = isRec,
                                        activeColor = Color(0xFFDC2626),
                                        enabled = callState.phase == CallPhase.CONNECTED || callState.phase == CallPhase.RECONNECTING,
                                        onClick = {
                                            if (isRec) onStopRecording() else onStartRecording()
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFDC2626),
                                    modifier = Modifier.size(62.dp)
                                ) {
                                    IconButton(onClick = onHangup) {
                                        Icon(Icons.Default.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(28.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingActiveHudPill(
    state: CallRecordingState,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "rec_pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "rec_dot_alpha"
    )
    val isPaused = state.status == CallRecordingStatus.PAUSED
    val secs = state.elapsedMs / 1000L

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF7F1D1D).copy(alpha = 0.94f),
        modifier = Modifier.border(1.dp, Color(0xFFEF4444), RoundedCornerShape(22.dp))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(if (isPaused) 1f else alpha)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isPaused) "PAUSED %02d:%02d".format(secs / 60, secs % 60) else "REC %02d:%02d".format(secs / 60, secs % 60),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                contentDescription = if (isPaused) "Resume recording" else "Pause recording",
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onPauseOrResume() }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop recording",
                tint = Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onStop() }
            )
        }
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color = Color(0xFF0F766E),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.45f)
    ) {
        Surface(
            shape = CircleShape,
            color = if (active) activeColor else Color.White.copy(alpha = 0.14f),
            modifier = Modifier.size(50.dp)
        ) {
            IconButton(onClick = { if (enabled) onClick() }, enabled = enabled) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
    }
}

@Composable
private fun WebRtcSurfaceView(
    modifier: Modifier,
    eglContext: EglBase.Context,
    mirror: Boolean,
    isZOrderMediaOverlay: Boolean = false,
    onAttach: (VideoSink) -> Unit,
    onDetach: (VideoSink) -> Unit
) {
    var rendererRef: SurfaceViewRenderer? = null

    DisposableEffect(eglContext) {
        onDispose {
            rendererRef?.let { r ->
                onDetach(r)
                runCatching { r.release() }
            }
            rendererRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                setZOrderMediaOverlay(isZOrderMediaOverlay)
                rendererRef = this
                onAttach(this)
            }
        },
        update = { view ->
            view.setMirror(mirror)
        }
    )
}

private fun formatCallDuration(secs: Long): String =
    "%02d:%02d".format(secs / 60, secs % 60)
