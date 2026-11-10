package com.systemutility.feature.chat.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.core.database.entity.MessageEntity
import com.systemutility.core.database.entity.ReactionEntity
import com.systemutility.core.model.DisappearingTimer
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.model.MediaCategory
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.chat.ChatUiState
import com.systemutility.feature.voice.VoicePlaybackState
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBackClick: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onReplyClick: (MessageEntity) -> Unit,
    onEditClick: (MessageEntity) -> Unit,
    onClearComposerMode: () -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onReactMessage: (String, String) -> Unit,
    onTogglePin: (MessageEntity) -> Unit,
    onForwardMessage: (MessageEntity) -> Unit,
    onDeleteForMe: (String) -> Unit,
    onDeleteForEveryone: (String) -> Unit,
    onRetryMessage: (String) -> Unit,
    onSetDisappearingTimer: (DisappearingTimer) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onOpenSafetyNumber: () -> Unit,
    onToggleVerified: (Boolean) -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onCreateCameraUri: () -> Uri,
    onGetFileProviderUri: (File) -> Uri,
    onSendMediaUri: (Uri, MediaCategory) -> Unit,
    onDownloadAttachment: (AttachmentEntity) -> Unit,
    onSaveAttachment: (AttachmentEntity) -> Unit,
    onStartVoiceRecording: () -> Unit,
    onStopAndSendVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onToggleVoicePlay: (AttachmentEntity) -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onSeekVoice: (String, Float) -> Unit,
    onOpenMediaGallery: () -> Unit,
    onDismissBanner: () -> Unit,
    onTrustNewSecurityKey: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showOverflowMenu by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var comingSoonFeatureTitle by remember { mutableStateOf<String?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var previewAttachment by remember { mutableStateOf<AttachmentEntity?>(null) }
    var contextMenuTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<MessageEntity?>(null) }

    val visualMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val cat = if (mime.startsWith("video")) MediaCategory.VIDEO else MediaCategory.IMAGE
            onSendMediaUri(uri, cat)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri).orEmpty()
            val cat = when {
                mime.startsWith("audio") -> MediaCategory.AUDIO
                mime.startsWith("image") -> MediaCategory.IMAGE
                mime.startsWith("video") -> MediaCategory.VIDEO
                else -> MediaCategory.DOCUMENT
            }
            onSendMediaUri(uri, cat)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            onSendMediaUri(uri, MediaCategory.IMAGE)
        }
        pendingCameraUri = null
    }

    val cameraPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = onCreateCameraUri()
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onStartVoiceRecording()
        }
    }

    val visibleMessages = remember(state.messages, state.searchActive, state.searchQuery) {
        if (!state.searchActive || state.searchQuery.isBlank()) {
            state.messages
        } else {
            val q = state.searchQuery.trim()
            state.messages.filter { !it.isDeleted && it.body.contains(q, ignoreCase = true) }
        }
    }

    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty() && !state.searchActive) {
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    val wallpaperBrush = remember(state.appearance.wallpaperId) {
        when (state.appearance.wallpaperId) {
            "obsidian_night" -> Brush.verticalGradient(listOf(Color(0xFF0A0F14), Color(0xFF101922)))
            "midnight_teal" -> Brush.verticalGradient(listOf(Color(0xFF0A1B1E), Color(0xFF0F292E)))
            else -> Brush.verticalGradient(listOf(Color(0xFF0B141A), Color(0xFF111B21)))
        }
    }

    Scaffold(
        topBar = {
            Column {
                if (state.searchActive) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { onToggleSearch(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
                            }
                        },
                        title = {
                            OutlinedTextField(
                                value = state.searchQuery,
                                onValueChange = onSearchQueryChange,
                                placeholder = { Text("Search messages…") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )
                        }
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    onOpenSafetyNumber()
                                    showSafetyDialog = true
                                }
                            ) {
                                val partnerName = state.partnerUser?.displayName ?: "Paired Partner"
                                val partnerAvatar = state.partnerUser?.avatarLocalPath
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (partnerAvatar != null && File(partnerAvatar).exists()) {
                                        AsyncImage(
                                            model = File(partnerAvatar),
                                            contentDescription = "Partner avatar",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = partnerName.firstOrNull()?.uppercaseChar()?.toString() ?: "P",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = partnerName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val partnerLastSeen = state.partnerUser?.lastSeenAt
                                    val sub = when {
                                        state.isPartnerTyping -> "typing…"
                                        state.isPartnerOnline -> "online"
                                        partnerLastSeen != null && partnerLastSeen > 0L ->
                                            "last seen ${formatRelativeTime(partnerLastSeen)}"
                                        else -> "End-to-end encrypted"
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (state.isPartnerTyping || state.isPartnerOnline) StatusGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        actions = {
                            if (state.disappearingSeconds > 0L) {
                                IconButton(onClick = { showTimerDialog = true }) {
                                    Icon(Icons.Default.Timer, contentDescription = "Disappearing timer", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = {
                                if (FeatureFlags.CALLS_ENABLED) onStartVideoCall()
                                else comingSoonFeatureTitle = "Video Calls"
                            }) {
                                Icon(
                                    Icons.Default.Videocam,
                                    contentDescription = if (FeatureFlags.CALLS_ENABLED) "Video call" else "Video call (Coming Soon)",
                                    tint = if (FeatureFlags.CALLS_ENABLED) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    }
                                )
                            }
                            IconButton(onClick = {
                                if (FeatureFlags.CALLS_ENABLED) onStartVoiceCall()
                                else comingSoonFeatureTitle = "Voice Calls"
                            }) {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = if (FeatureFlags.CALLS_ENABLED) "Voice call" else "Voice call (Coming Soon)",
                                    tint = if (FeatureFlags.CALLS_ENABLED) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    }
                                )
                            }
                            Box {
                                IconButton(onClick = { showOverflowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                }
                                DropdownMenu(
                                    expanded = showOverflowMenu,
                                    onDismissRequest = { showOverflowMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Search") },
                                        leadingIcon = { Icon(Icons.Default.Search, null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            onToggleSearch(true)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Disappearing messages") },
                                        leadingIcon = { Icon(Icons.Default.Timer, null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            showTimerDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Safety number") },
                                        leadingIcon = { Icon(Icons.Default.Security, null) },
                                        onClick = {
                                            showOverflowMenu = false
                                            onOpenSafetyNumber()
                                            showSafetyDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Media & links") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onOpenMediaGallery()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (state.isMuted) "Unmute notifications" else "Mute notifications") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onToggleMute(!state.isMuted)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }

                // Pinned Message Banner
                state.pinnedMessage?.let { pinned ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val idx = visibleMessages.indexOfFirst { it.id == pinned.id }
                                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = pinned.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onTogglePin(pinned) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Unpin", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Info / Error Banner
                state.infoBanner?.let { banner ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(banner, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = onDismissBanner, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(wallpaperBrush)
                .imePadding()
                .navigationBarsPadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "e2ee_notice") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color(0xFF182229).copy(alpha = 0.88f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, null, tint = Color(0xFFFFD279), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Messages are end-to-end encrypted with Signal Protocol (PQXDH).",
                                    color = Color(0xFFD9DEE3),
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                itemsIndexed(visibleMessages, key = { _, msg -> msg.id }) { idx, msg ->
                    val prev = visibleMessages.getOrNull(idx - 1)
                    if (prev == null || !isSameDay(prev.createdAt, msg.createdAt)) {
                        DateSeparatorChip(msg.createdAt)
                    }

                    val isOwn = msg.senderUserId == state.selfUser?.id
                    val reactions = state.reactionsByMessage[msg.id].orEmpty()
                    val attachment = state.attachmentsByMessage[msg.id]

                    MessageBubbleItem(
                        message = msg,
                        attachment = attachment,
                        voicePlayback = state.voicePlayback,
                        isOwn = isOwn,
                        reactions = reactions,
                        selfUserId = state.selfUser?.id.orEmpty(),
                        outgoingColor = Color(state.appearance.outgoingBubbleHex),
                        incomingColor = Color(state.appearance.incomingBubbleHex),
                        cornerRadiusDp = state.appearance.bubbleCornerRadiusDp,
                        fontScale = state.appearance.fontScale,
                        onLongPress = { contextMenuTarget = msg },
                        onReplyQuoteClick = { replyId ->
                            val targetIdx = visibleMessages.indexOfFirst { it.id == replyId }
                            if (targetIdx >= 0) scope.launch { listState.animateScrollToItem(targetIdx) }
                        },
                        onReactionClick = { emoji -> onReactMessage(msg.id, emoji) },
                        onRetryClick = { onRetryMessage(msg.id) },
                        onDownloadAttachment = onDownloadAttachment,
                        onOpenAttachment = { att ->
                            if (att.category == "image") {
                                previewAttachment = att
                            } else {
                                val path = att.localPlainPath
                                if (path != null && File(path).exists()) {
                                    runCatching {
                                        val providerUri = onGetFileProviderUri(File(path))
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(providerUri, att.mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(intent)
                                    }
                                } else {
                                    onDownloadAttachment(att)
                                }
                            }
                        },
                        onToggleVoicePlay = onToggleVoicePlay,
                        onCycleVoiceSpeed = onCycleVoiceSpeed,
                        onSeekVoice = onSeekVoice
                    )
                }
            }

            // Reply / Edit Composer Context Strip
            val activeReply = state.replyingTo
            val activeEdit = state.editingMessage
            if (activeReply != null || activeEdit != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (activeEdit != null) "Editing message" else "Replying to message",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = (activeEdit?.body ?: activeReply?.body).orEmpty(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = onClearComposerMode) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                }
            }

            // Bottom Message Composer Bar (switches cleanly to live voice waveform recorder when recording)
            if (state.voiceRecording.isRecording) {
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = Color(0xFF1F2C34),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF5252))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val secs = state.voiceRecording.elapsedMs / 1000
                        Text(
                            text = "%02d:%02d".format(secs / 60, secs % 60),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            state.voiceRecording.liveBars.takeLast(24).forEach { bar ->
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(((bar / 100f) * 24f).coerceAtLeast(4f).dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                        TextButton(onClick = onCancelVoice) {
                            Text("Cancel", color = Color(0xFFFF8A80))
                        }
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        ) {
                            IconButton(onClick = onStopAndSendVoice) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send voice note", tint = Color.White)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = Color(0xFF1F2C34),
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showAttachSheet = true }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = Color(0xFF9AA5AE))
                            }
                            OutlinedTextField(
                                value = state.draftText,
                                onValueChange = onDraftChange,
                                placeholder = { Text("Message", color = Color(0xFF8696A0)) },
                                maxLines = 5,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        val hasText = state.draftText.isNotBlank()
                        IconButton(
                            onClick = {
                                if (hasText) {
                                    onSendClick()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) onStartVoiceRecording()
                                    else audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic,
                                contentDescription = if (hasText) "Send" else "Record voice message",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    // Long-press Message Context Bottom Sheet
    contextMenuTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { contextMenuTarget = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                // Quick Emoji Reaction Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 26.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable {
                                    onReactMessage(target.id, emoji)
                                    contextMenuTarget = null
                                }
                                .padding(6.dp)
                        )
                    }
                }
                HorizontalDivider()

                if (!target.isDeleted) {
                    ListItem(
                        headlineContent = { Text("Reply") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, null) },
                        modifier = Modifier.clickable {
                            onReplyClick(target)
                            contextMenuTarget = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Copy text") },
                        leadingContent = { Icon(Icons.Default.ContentCopy, null) },
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(target.body))
                            contextMenuTarget = null
                        }
                    )
                    if (target.senderUserId == state.selfUser?.id && target.kind == "TEXT") {
                        ListItem(
                            headlineContent = { Text("Edit") },
                            leadingContent = { Icon(Icons.Default.Edit, null) },
                            modifier = Modifier.clickable {
                                onEditClick(target)
                                contextMenuTarget = null
                            }
                        )
                    }
                    ListItem(
                        headlineContent = { Text(if (target.isPinned) "Unpin" else "Pin to top") },
                        leadingContent = { Icon(Icons.Default.PushPin, null) },
                        modifier = Modifier.clickable {
                            onTogglePin(target)
                            contextMenuTarget = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Forward") },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Forward, null) },
                        modifier = Modifier.clickable {
                            onForwardMessage(target)
                            contextMenuTarget = null
                        }
                    )
                }
                ListItem(
                    headlineContent = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        deleteConfirmTarget = target
                        contextMenuTarget = null
                    }
                )
            }
        }
    }

    // Delete confirmation (For Me / For Everyone)
    deleteConfirmTarget?.let { msg ->
        val isOwn = msg.senderUserId == state.selfUser?.id
        AlertDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = { Text("Delete message?") },
            text = { Text("Remove this message from your device" + if (isOwn) " or for both participants?" else "?") },
            confirmButton = {
                if (isOwn) {
                    TextButton(onClick = {
                        onDeleteForEveryone(msg.id)
                        deleteConfirmTarget = null
                    }) {
                        Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDeleteForMe(msg.id)
                        deleteConfirmTarget = null
                    }) {
                        Text("Delete for me")
                    }
                    TextButton(onClick = { deleteConfirmTarget = null }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }

    // Disappearing Messages Timer Dialog
    if (showTimerDialog) {
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("Disappearing messages") },
            text = {
                Column {
                    DisappearingTimer.entries.forEach { timer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetDisappearingTimer(timer)
                                    showTimerDialog = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = state.disappearingSeconds == timer.seconds,
                                onClick = {
                                    onSetDisappearingTimer(timer)
                                    showTimerDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(timer.label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTimerDialog = false }) { Text("Close") }
            }
        )
    }

    // Attachment Type Picker Sheet
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text("Photo or Video") },
                    supportingContent = { Text("End-to-end encrypted via AES-256-GCM") },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        visualMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                )
                ListItem(
                    headlineContent = { Text("Take Photo") },
                    leadingContent = { Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        val camGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (camGranted) {
                            val uri = onCreateCameraUri()
                            pendingCameraUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
                ListItem(
                    headlineContent = { Text("Audio File") },
                    leadingContent = { Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        documentPicker.launch(arrayOf("audio/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text("Document") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        documentPicker.launch(arrayOf("*/*"))
                    }
                )
            }
        }
    }

    // Full Photo Viewer Dialog
    previewAttachment?.let { att ->
        AlertDialog(
            onDismissRequest = { previewAttachment = null },
            title = { Text(att.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                val p = att.localPlainPath
                if (p != null && File(p).exists()) {
                    AsyncImage(
                        model = File(p),
                        contentDescription = att.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveAttachment(att)
                    previewAttachment = null
                }) {
                    Text("Save to Downloads")
                }
            },
            dismissButton = {
                TextButton(onClick = { previewAttachment = null }) {
                    Text("Close")
                }
            }
        )
    }

    // Safety Number Quick Sheet
    if (showSafetyDialog) {
        AlertDialog(
            onDismissRequest = { showSafetyDialog = false },
            icon = {
                Icon(
                    imageVector = if (state.isIdentityVerified) Icons.Default.VerifiedUser else Icons.Default.Security,
                    contentDescription = null,
                    tint = if (state.isIdentityVerified) StatusGreen else MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("Verify Security Code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Compare this 60-digit Signal Safety Number with ${state.partnerUser?.displayName ?: "your partner"} in person or via call:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = state.safetyNumber ?: "Key exchange completes on first message",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    if (state.safetyNumber != null) {
                        OutlinedButton(
                            onClick = { onToggleVerified(!state.isIdentityVerified) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.isIdentityVerified) "Verified (Tap to Clear)" else "Mark as Verified")
                        }
                    }
                    TextButton(
                        onClick = {
                            onTrustNewSecurityKey()
                            showSafetyDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Partner Reinstalled? Trust New Key & Resync")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyDialog = false }) { Text("Done") }
            }
        )
    }

    comingSoonFeatureTitle?.let { featureTitle ->
        AlertDialog(
            onDismissRequest = { comingSoonFeatureTitle = null },
            icon = {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("$featureTitle — Coming Soon") },
            text = {
                Text(
                    "Encrypted WebRTC voice & video calling and call recording are coming soon. " +
                        "Private encrypted text chat, photos, voice notes, and file sharing are live right now."
                )
            },
            confirmButton = {
                TextButton(onClick = { comingSoonFeatureTitle = null }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun DateSeparatorChip(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF182229).copy(alpha = 0.88f),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = formatDayLabel(timestamp),
                color = Color(0xFFB4C0C8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleItem(
    message: MessageEntity,
    attachment: AttachmentEntity?,
    voicePlayback: VoicePlaybackState,
    isOwn: Boolean,
    reactions: List<ReactionEntity>,
    selfUserId: String,
    outgoingColor: Color,
    incomingColor: Color,
    cornerRadiusDp: Int,
    fontScale: Float,
    onLongPress: () -> Unit,
    onReplyQuoteClick: (String) -> Unit,
    onReactionClick: (String) -> Unit,
    onRetryClick: () -> Unit,
    onDownloadAttachment: (AttachmentEntity) -> Unit,
    onOpenAttachment: (AttachmentEntity) -> Unit,
    onToggleVoicePlay: (AttachmentEntity) -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onSeekVoice: (String, Float) -> Unit
) {
    val bgColor = if (isOwn) outgoingColor else incomingColor
    val corner = cornerRadiusDp.coerceIn(6, 24).dp
    val bubbleShape = RoundedCornerShape(
        topStart = corner,
        topEnd = corner,
        bottomStart = if (isOwn) corner else 4.dp,
        bottomEnd = if (isOwn) 4.dp else corner
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bgColor,
            modifier = Modifier
                .widthIn(min = 84.dp, max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                if (message.isForwarded && !message.isDeleted) {
                    Text(
                        text = "Forwarded",
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                val replyId = message.replyToMessageId
                if (replyId != null && !message.isDeleted) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .clickable { onReplyQuoteClick(replyId) }
                    ) {
                        Text(
                            text = message.replyPreviewText ?: "Original message",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                if (attachment != null && !message.isDeleted) {
                    AttachmentBubbleContent(
                        attachment = attachment,
                        voicePlayback = voicePlayback,
                        onDownload = { onDownloadAttachment(attachment) },
                        onOpen = { onOpenAttachment(attachment) },
                        onToggleVoicePlay = { onToggleVoicePlay(attachment) },
                        onCycleVoiceSpeed = onCycleVoiceSpeed,
                        onSeekVoice = { fraction -> onSeekVoice(attachment.id, fraction) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (message.kind == "TEXT" || message.isDeleted || attachment == null) {
                    Text(
                        text = message.body,
                        color = if (message.isDeleted) Color.White.copy(alpha = 0.65f) else Color.White,
                        fontStyle = if (message.isDeleted) FontStyle.Italic else FontStyle.Normal,
                        fontSize = (15f * fontScale).sp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.disappearsAt != null && !message.isDeleted) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = "Disappearing",
                            tint = Color.White.copy(alpha = 0.68f),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    if (message.isEdited && !message.isDeleted) {
                        Text(
                            text = "Edited",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                    }
                    Text(
                        text = formatHourMinute(message.createdAt),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                    if (isOwn) {
                        when (message.status) {
                            "QUEUED" -> Icon(Icons.Default.Schedule, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(13.dp))
                            "SENT" -> Icon(Icons.Default.Done, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(14.dp))
                            "DELIVERED" -> Icon(Icons.Default.DoneAll, null, tint = Color.White.copy(alpha = 0.72f), modifier = Modifier.size(14.dp))
                            "READ" -> Icon(Icons.Default.DoneAll, null, tint = Color(0xFF53BDEB), modifier = Modifier.size(14.dp))
                            else -> Icon(
                                Icons.Default.ErrorOutline,
                                "Retry",
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onRetryClick() }
                            )
                        }
                    }
                }
            }
        }

        if (reactions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                reactions.groupBy { it.emoji }.forEach { (emoji, list) ->
                    val includesSelf = list.any { it.userId == selfUserId }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (includesSelf) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else Color(0xFF1F2C34),
                        modifier = Modifier.clickable { onReactionClick(emoji) }
                    ) {
                        Text(
                            text = if (list.size > 1) "$emoji ${list.size}" else emoji,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun isSameDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
        c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun formatDayLabel(ts: Long): String {
    val cal = Calendar.getInstance()
    val todayYear = cal.get(Calendar.YEAR)
    val todayDay = cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = ts
    return when {
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.DAY_OF_YEAR) == todayDay -> "Today"
        cal.get(Calendar.YEAR) == todayYear && cal.get(Calendar.DAY_OF_YEAR) == todayDay - 1 -> "Yesterday"
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ts))
    }
}

private fun formatHourMinute(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatRelativeTime(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))

@Composable
private fun AttachmentBubbleContent(
    attachment: AttachmentEntity,
    voicePlayback: VoicePlaybackState,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onToggleVoicePlay: () -> Unit,
    onCycleVoiceSpeed: () -> Unit,
    onSeekVoice: (Float) -> Unit
) {
    val localPath = attachment.localPlainPath
    val localExists = localPath != null && File(localPath).exists()

    when (attachment.category) {
        "image" -> {
            val preview = if (localExists) localPath else attachment.thumbnailPath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .clickable { if (localExists) onOpen() else onDownload() },
                contentAlignment = Alignment.Center
            ) {
                if (preview != null && File(preview).exists()) {
                    AsyncImage(
                        model = File(preview),
                        contentDescription = attachment.fileName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!localExists) {
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f)) {
                        Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(formatBytesCompact(attachment.sizeBytes), color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                if (attachment.transferState == "DOWNLOADING" || attachment.transferState == "UPLOADING") {
                    LinearProgressIndicator(
                        progress = { attachment.progressPercent / 100f },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    )
                }
            }
        }

        "voice" -> {
            val isThisActive = voicePlayback.activeAttachmentId == attachment.id
            val isPlaying = isThisActive && voicePlayback.isPlaying
            val totalMs = if (isThisActive && voicePlayback.durationMs > 0L) voicePlayback.durationMs else attachment.durationMs.coerceAtLeast(1000L)
            val progressFrac = if (isThisActive) (voicePlayback.positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
            val bars = remember(attachment.waveformCsv) {
                attachment.waveformCsv.split(",").mapNotNull { it.toIntOrNull() }.ifEmpty { List(24) { 35 } }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleVoicePlay, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        bars.forEachIndexed { idx, amp ->
                            val frac = (idx + 1).toFloat() / bars.size
                            val filled = frac <= progressFrac
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(((amp / 100f) * 24f).coerceAtLeast(4f).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (filled) Color.White else Color.White.copy(alpha = 0.38f))
                                    .clickable { onSeekVoice(frac) }
                            )
                        }
                    }
                    val shownMs = if (isThisActive && isPlaying) voicePlayback.positionMs else totalMs
                    val secs = shownMs / 1000
                    Text(
                        text = "%02d:%02d".format(secs / 60, secs % 60),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.28f),
                    modifier = Modifier.clickable { onCycleVoiceSpeed() }
                ) {
                    Text(
                        text = if (voicePlayback.speed == 1.0f) "1x" else "${voicePlayback.speed}x",
                        fontSize = 11.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }

        else -> {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color.Black.copy(alpha = 0.22f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (localExists) onOpen() else onDownload() }
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (attachment.category == "audio") Icons.Default.AudioFile else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(attachment.fileName, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${formatBytesCompact(attachment.sizeBytes)} • ${if (localExists) "Tap to open" else "Tap to decrypt"}",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp
                            )
                        }
                        if (!localExists) {
                            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (attachment.transferState == "DOWNLOADING" || attachment.transferState == "UPLOADING") {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { attachment.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun formatBytesCompact(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
