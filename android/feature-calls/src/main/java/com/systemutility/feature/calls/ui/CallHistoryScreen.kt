package com.systemutility.feature.calls.ui

import android.media.MediaPlayer
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.systemutility.core.database.entity.CallHistoryEntity
import com.systemutility.core.database.entity.CallRecordingEntity
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.ui.theme.StatusAmber
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.calls.CallHistoryUiState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryScreen(
    state: CallHistoryUiState,
    onBackClick: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onPlayRecording: (CallRecordingEntity) -> Unit,
    onClosePlayback: () -> Unit,
    onDeleteRecording: (CallRecordingEntity) -> Unit,
    onClearNotice: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var deleteConfirmTarget by remember { mutableStateOf<CallRecordingEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (FeatureFlags.CALLS_ENABLED) "Calls & Recordings" else "Calls", fontWeight = FontWeight.Bold) },
                actions = {
                    if (FeatureFlags.CALLS_ENABLED) {
                        IconButton(onClick = onStartVoiceCall) {
                            Icon(Icons.Default.Call, contentDescription = "Voice call")
                        }
                        IconButton(onClick = onStartVideoCall) {
                            Icon(Icons.Default.Videocam, contentDescription = "Video call")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!FeatureFlags.CALLS_ENABLED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(StatusAmber.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                tint = StatusAmber,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusAmber.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "COMING SOON",
                                color = StatusAmber,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = "Voice & Video Calls",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "End-to-end encrypted WebRTC voice calls, video calls, and encrypted call recordings are planned for an upcoming release. Encrypted chat, photos, voice notes, and file sharing are fully active right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(onClick = onBackClick) {
                            Text("Back")
                        }
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            state.statusNotice?.let { notice ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(notice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClearNotice, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Call Log") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Saved Recordings") })
            }

            when (selectedTab) {
                0 -> {
                    if (state.callHistory.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No encrypted calls recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(state.callHistory, key = { it.id }) { item ->
                                CallLogCard(
                                    entry = item,
                                    partnerName = state.partnerUser?.displayName ?: "Paired Partner",
                                    onRedial = {
                                        if (item.callType == "video") onStartVideoCall() else onStartVoiceCall()
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (state.recordings.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(34.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "Manual call recordings are encrypted locally in your app vault.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(state.recordings, key = { it.id }) { rec ->
                                CallRecordingCard(
                                    recording = rec,
                                    onPlay = { onPlayRecording(rec) },
                                    onDelete = { deleteConfirmTarget = rec }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Encrypted Local Recording Playback Dialog
    val activeRec = state.activePlaybackRecording
    val activeFile = state.activePlaybackFile
    if (activeRec != null && activeFile != null) {
        RecordingPlaybackDialog(
            recording = activeRec,
            plainFile = activeFile,
            onDismiss = onClosePlayback
        )
    }

    deleteConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = { Text("Delete Encrypted Recording?") },
            text = { Text("This permanently erases the encrypted call recording from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteRecording(target)
                    deleteConfirmTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CallLogCard(
    entry: CallHistoryEntity,
    partnerName: String,
    onRedial: () -> Unit
) {
    val isMissed = entry.status == "MISSED" || entry.status == "REJECTED" || entry.status == "FAILED"
    val icon = when {
        isMissed -> Icons.AutoMirrored.Filled.CallMissed
        entry.isLocalInitiated -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val tint = if (isMissed) MaterialTheme.colorScheme.error else StatusGreen

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(partnerName, fontWeight = FontWeight.SemiBold)
                val durText = if (entry.answeredAt != null && entry.endedAt != null && entry.endedAt!! >= entry.answeredAt!!) {
                    val s = (entry.endedAt!! - entry.answeredAt!!) / 1000L
                    " • %02d:%02d".format(s / 60, s % 60)
                } else ""
                Text(
                    text = "${if (entry.callType == "video") "Video" else "Voice"} • ${entry.status}$durText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(entry.startedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRedial) {
                Icon(
                    imageVector = if (entry.callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                    contentDescription = "Redial",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun CallRecordingCard(
    recording: CallRecordingEntity,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val secs = recording.durationMs / 1000L
    val isVideo = recording.recordingKind == "video"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isVideo) Icons.Default.VideoFile else Icons.Default.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isVideo) "Video Recording" else "Voice Recording",
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Lock, contentDescription = "Encrypted", tint = StatusGreen, modifier = Modifier.size(13.dp))
                }
                Text(
                    text = "%02d:%02d • %d KB".format(secs / 60, secs % 60, (recording.sizeBytes / 1024L).coerceAtLeast(1L)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(recording.startedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RecordingPlaybackDialog(
    recording: CallRecordingEntity,
    plainFile: File,
    onDismiss: () -> Unit
) {
    val isVideo = recording.recordingKind == "video" && plainFile.extension.equals("mp4", ignoreCase = true)
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(plainFile.absolutePath) {
        if (!isVideo) {
            val mp = MediaPlayer().apply {
                runCatching {
                    setDataSource(plainFile.absolutePath)
                    prepare()
                    setOnCompletionListener { isPlaying = false }
                    start()
                    isPlaying = true
                }
            }
            mediaPlayer = mp
        }
        onDispose {
            runCatching {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Encrypted Recording")
            }
        },
        text = {
            if (isVideo) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            val vv = this
                            setVideoPath(plainFile.absolutePath)
                            setMediaController(MediaController(ctx).apply { setAnchorView(vv) })
                            start()
                        }
                    }
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val secs = recording.durationMs / 1000L
                    Text(
                        text = "%02d:%02d".format(secs / 60, secs % 60),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        IconButton(
                            onClick = {
                                val mp = mediaPlayer ?: return@IconButton
                                if (mp.isPlaying) {
                                    mp.pause()
                                    isPlaying = false
                                } else {
                                    mp.start()
                                    isPlaying = true
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Toggle Play",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
