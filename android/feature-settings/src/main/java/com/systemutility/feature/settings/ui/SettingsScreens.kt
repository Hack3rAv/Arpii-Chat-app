package com.systemutility.feature.settings.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.systemutility.core.model.DisappearingTimer
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.model.ThemeMode
import com.systemutility.core.ui.theme.StatusAmber
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.settings.SettingsUiState
import com.systemutility.feature.settings.update.UpdatePhase
import kotlin.math.roundToInt

private val ACCENT_SWATCHES = listOf(0xFF0F766EL, 0xFF0284C7L, 0xFF7C3AEDL, 0xFF059669L, 0xFFD97706L)
private val BUBBLE_OUT_SWATCHES = listOf(0xFF0F766EL, 0xFF1D4ED8L, 0xFF6D28D9L, 0xFF065F46L, 0xFF334155L)
private val BUBBLE_IN_SWATCHES = listOf(0xFF1F2937L, 0xFF1E293BL, 0xFF27272AL, 0xFF172554L, 0xFF312E81L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenSafetyNumber: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onLogoutConfirmed: () -> Unit,
    onOpenUpdates: () -> Unit = {}
) {
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    ListItem(
                        headlineContent = { Text(state.selfUser?.displayName ?: "Profile", fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(state.selfUser?.about?.ifBlank { "@${state.selfUser.username}" } ?: "Edit display name, status & avatar") },
                        leadingContent = { Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenProfile() }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Appearance & Chat Theme") },
                        supportingContent = { Text("Theme mode, bubble colors, font size & wallpaper") },
                        leadingContent = { Icon(Icons.Default.Palette, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenAppearance() }
                    )
                    ListItem(
                        headlineContent = { Text("Notifications & Stealth Alerts") },
                        supportingContent = { Text("WebSocket live alerts, WorkManager sync, masking") },
                        leadingContent = { Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenNotifications() }
                    )
                    ListItem(
                        headlineContent = { Text("Privacy, App Lock & Security") },
                        supportingContent = { Text("Read receipts, PIN/biometric lock, screenshot shield") },
                        leadingContent = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenPrivacy() }
                    )
                    ListItem(
                        headlineContent = { Text("Signal Safety Number") },
                        supportingContent = { Text("Verify partner's PQXDH cryptographic fingerprint") },
                        leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenSafetyNumber() }
                    )
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Backup & Restore")
                                if (!FeatureFlags.BACKUP_ENABLED) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = StatusAmber.copy(alpha = 0.16f)
                                    ) {
                                        Text(
                                            text = "Coming Soon",
                                            color = StatusAmber,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                if (FeatureFlags.BACKUP_ENABLED) "Encrypted backups & recovery key"
                                else "Upcoming release • Local vault stays persistent"
                            )
                        },
                        leadingContent = { Icon(Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenBackup() }
                    )
                    ListItem(
                        headlineContent = { Text("Active Sessions & Devices") },
                        supportingContent = { Text("Manage authorized phones and revoke access") },
                        leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenSessions() }
                    )
                    ListItem(
                        headlineContent = { Text("Change Account Password") },
                        supportingContent = { Text("Rotate Argon2id password verifier") },
                        leadingContent = { Icon(Icons.Default.Password, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenChangePassword() }
                    )
                    val rel = state.updateState.availableRelease
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("App Updates (Git Releases)")
                                if (rel != null) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = StatusGreen.copy(alpha = 0.18f)
                                    ) {
                                        Text(
                                            text = rel.tagName,
                                            color = StatusGreen,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        supportingContent = {
                            val sub = when {
                                rel != null -> "Update available • v${state.updateState.installedVersionName} -> ${rel.tagName}"
                                state.updateState.phase == UpdatePhase.CHECKING -> "Checking GitHub Releases…"
                                state.updateState.effectiveRepo.isNotBlank() ->
                                    "Installed v${state.updateState.installedVersionName} • ${state.updateState.effectiveRepo}"
                                else -> "Installed v${state.updateState.installedVersionName} • Tap to configure GitHub repo"
                            }
                            Text(sub)
                        },
                        leadingContent = { Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenUpdates() }
                    )
                }
            }

            Button(
                onClick = { confirmLogout = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out of This Device")
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Sign Out?") },
            text = { Text("Your local SQLCipher vault stays intact until unlocked again, and this device's server token will be revoked.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogoutConfirmed()
                }) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("Cancel") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onAccentHex: (Long) -> Unit,
    onOutgoingHex: (Long) -> Unit,
    onIncomingHex: (Long) -> Unit,
    onCornerRadius: (Int) -> Unit,
    onFontScale: (Float) -> Unit,
    onWallpaper: (String) -> Unit
) {
    val cfg = state.appearance
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Appearance", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Bubble Preview Card
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF0E171D))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live Chat Preview", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        Surface(
                            color = Color(cfg.incomingBubbleHex),
                            shape = RoundedCornerShape(cfg.bubbleCornerRadiusDp.dp)
                        ) {
                            Text(
                                "End-to-end encrypted preview",
                                color = Color.White,
                                fontSize = (14f * cfg.fontScale).sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Surface(
                            color = Color(cfg.outgoingBubbleHex),
                            shape = RoundedCornerShape(cfg.bubbleCornerRadiusDp.dp)
                        ) {
                            Text(
                                "Looks sharp and private.",
                                color = Color.White,
                                fontSize = (14f * cfg.fontScale).sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Theme Mode
            Text("Theme Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = cfg.themeMode == mode,
                        onClick = { onThemeMode(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercaseChar() }) }
                    )
                }
            }

            ColorSwatchRow("Accent Color", ACCENT_SWATCHES, cfg.accentHex, onAccentHex)
            ColorSwatchRow("Outgoing Bubble", BUBBLE_OUT_SWATCHES, cfg.outgoingBubbleHex, onOutgoingHex)
            ColorSwatchRow("Incoming Bubble", BUBBLE_IN_SWATCHES, cfg.incomingBubbleHex, onIncomingHex)

            Column {
                Text("Bubble Corner Radius: ${cfg.bubbleCornerRadiusDp} dp", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = cfg.bubbleCornerRadiusDp.toFloat(),
                    onValueChange = { onCornerRadius(it.roundToInt()) },
                    valueRange = 6f..24f
                )
            }

            Column {
                Text("Message Text Scale: ${(cfg.fontScale * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = cfg.fontScale,
                    onValueChange = onFontScale,
                    valueRange = 0.85f..1.35f
                )
            }

            Text("Chat Wallpaper", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val wallpapers = listOf("minimal_slate" to "Minimal Slate", "obsidian_night" to "Obsidian Night", "midnight_teal" to "Midnight Teal")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wallpapers.forEach { (id, label) ->
                    FilterChip(
                        selected = cfg.wallpaperId == id,
                        onClick = { onWallpaper(id) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchRow(
    title: String,
    options: List<Long>,
    selectedHex: Long,
    onSelect: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { hex ->
                val selected = hex == selectedHex
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(hex))
                        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.Gray.copy(alpha = 0.4f), CircleShape)
                        .clickable { onSelect(hex) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onStealthChange: (Boolean) -> Unit,
    onPreviewChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onRingtoneChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onMuteChange: (Boolean) -> Unit,
    onDndChange: (Boolean) -> Unit
) {
    val n = state.notifications
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Notifications", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SwitchRow("Discreet System Utility Mask", "Mask lock-screen alerts as 'Background sync notification'", n.stealthNotificationText, onStealthChange)
            SwitchRow("Show Message Previews", "Display decrypted snippet when unmasked", n.showNotificationPreviews, onPreviewChange)
            SwitchRow("Message Alert Sound", "Play tone on incoming messages", n.messageSoundEnabled, onSoundChange)
            SwitchRow(
                title = if (FeatureFlags.CALLS_ENABLED) "Call Ringtone" else "Call Ringtone (Coming Soon)",
                subtitle = if (FeatureFlags.CALLS_ENABLED) "Play ringtone for incoming encrypted calls"
                else "Encrypted voice & video calls will unlock in a future release",
                checked = if (FeatureFlags.CALLS_ENABLED) n.callRingtoneEnabled else false,
                onCheckedChange = onRingtoneChange,
                enabled = FeatureFlags.CALLS_ENABLED
            )
            SwitchRow("Vibration", "Haptic feedback on incoming alerts", n.vibrateEnabled, onVibrateChange)
            SwitchRow("Mute Conversation", "Silence message alerts for paired thread", n.muteConversation, onMuteChange)
            SwitchRow("Do Not Disturb", "Suppress all call & message notifications locally", n.dndEnabled, onDndChange)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "Delivery Architecture: Alerts arrive over authenticated WebSockets while connected, backed by WorkManager deferred sync. Instant background wakeup when Android terminates or force-stops the app is disabled in this Firebase-free release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyAndSecurityScreen(
    state: SettingsUiState,
    onBackClick: () -> Unit,
    onReadReceipts: (Boolean) -> Unit,
    onTypingIndicators: (Boolean) -> Unit,
    onShowOnline: (Boolean) -> Unit,
    onShowLastSeen: (Boolean) -> Unit,
    onScreenshotProtection: (Boolean) -> Unit,
    onDisappearingTimer: (DisappearingTimer) -> Unit,
    onSetupPin: (String) -> Unit,
    onDisableAppLock: () -> Unit,
    onBiometricLock: (Boolean) -> Unit,
    onOpenSafetyNumber: () -> Unit,
    onClearNotice: () -> Unit
) {
    val p = state.privacy
    var showPinDialog by remember { mutableStateOf(false) }
    var showTimerDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Privacy & Security", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            state.statusNotice?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearNotice) { Text("OK") }
                    }
                }
            }

            SwitchRow("Read Receipts", "Send blue ticks when viewing partner's messages", p.sendReadReceipts, onReadReceipts)
            SwitchRow("Typing Indicators", "Show when you are composing a message", p.sendTypingIndicators, onTypingIndicators)
            SwitchRow("Online Status", "Allow partner to see active connection status", p.showOnlineStatus, onShowOnline)
            SwitchRow("Last Seen Timestamp", "Share last active timestamp with partner", p.showLastSeen, onShowLastSeen)
            SwitchRow("Screenshot Shield (FLAG_SECURE)", "Prevent screenshots and hide screen preview in Recents", p.screenshotProtectionEnabled, onScreenshotProtection)

            val timerLabel = DisappearingTimer.entries.firstOrNull { it.seconds == p.disappearingTimerSeconds }?.label ?: "Off"
            ListItem(
                headlineContent = { Text("Default Disappearing Timer") },
                supportingContent = { Text(timerLabel) },
                modifier = Modifier.clickable { showTimerDialog = true }
            )

            HorizontalDivider()

            SwitchRow(
                title = "App Lock PIN",
                subtitle = if (p.appLockEnabled) "Enabled (prompts on launch/resume)" else "Require 4–6 digit PIN to open System Utility",
                checked = p.appLockEnabled,
                onCheckedChange = { enable ->
                    if (enable) {
                        pinInput = ""
                        showPinDialog = true
                    } else {
                        onDisableAppLock()
                    }
                }
            )
            if (p.appLockEnabled) {
                ListItem(
                    headlineContent = { Text("Change App Lock PIN") },
                    modifier = Modifier.clickable {
                        pinInput = ""
                        showPinDialog = true
                    }
                )
            }
            SwitchRow("Biometric Unlock", "Use Fingerprint / Face unlock when App Lock is active", p.biometricLockEnabled, onBiometricLock)

            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Verify Safety Number") },
                supportingContent = { Text("Compare Signal Protocol key fingerprint with partner") },
                leadingContent = { Icon(Icons.Default.Security, null, tint = StatusGreen) },
                modifier = Modifier.clickable { onOpenSafetyNumber() }
            )
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("Configure App Lock PIN") },
            text = {
                Column {
                    Text("Enter 4 to 6 numeric digits protected locally with Argon2id + Android Keystore.")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) pinInput = it },
                        label = { Text("PIN (4–6 digits)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pinInput.length >= 4,
                    onClick = {
                        onSetupPin(pinInput)
                        showPinDialog = false
                    }
                ) { Text("Save PIN") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTimerDialog) {
        AlertDialog(
            onDismissRequest = { showTimerDialog = false },
            title = { Text("Default Disappearing Timer") },
            text = {
                Column {
                    DisappearingTimer.entries.forEach { timer ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDisappearingTimer(timer)
                                    showTimerDialog = false
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = p.disappearingTimerSeconds == timer.seconds,
                                onClick = {
                                    onDisappearingTimer(timer)
                                    showTimerDialog = false
                                }
                            )
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
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberScreen(
    state: SettingsUiState,
    onLoad: () -> Unit,
    onToggleVerified: (Boolean) -> Unit,
    onBackClick: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { onLoad() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Safety Number", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = if (state.isPartnerIdentityVerified) Icons.Default.VerifiedUser else Icons.Default.Security,
                contentDescription = null,
                tint = if (state.isPartnerIdentityVerified) StatusGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = "Partner: ${state.partnerUser?.displayName ?: "Paired Partner"}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Compare this 60-digit Signal Protocol fingerprint in person or via a verified call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = state.safetyNumber ?: "Send or receive at least one encrypted message first to establish the partner identity session.",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    lineHeight = 28.sp,
                    modifier = Modifier.padding(18.dp)
                )
            }

            if (state.safetyNumber != null) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.safetyNumber)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Safety Number")
                }

                Button(
                    onClick = { onToggleVerified(!state.isPartnerIdentityVerified) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isPartnerIdentityVerified) "Verified Partner (Tap to Clear)" else "Mark Partner Identity as Verified")
                }
            }
        }
    }
}

@Composable
fun AppLockOverlayScreen(
    biometricEnabled: Boolean,
    onVerifyPin: (String) -> Boolean,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun triggerBiometric() {
        val activity = context as? FragmentActivity ?: return
        val bm = BiometricManager.from(activity)
        val canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) return

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    errorMsg = errString.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock System Utility")
            .setSubtitle("Verify identity to access encrypted messages")
            .setNegativeButtonText("Use PIN")
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) triggerBiometric()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(14.dp))
            Text("System Utility Locked", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Enter your App Lock PIN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    if (it.length <= 6 && it.all(Char::isDigit)) {
                        pin = it
                        errorMsg = null
                    }
                },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(0.7f)
            )

            errorMsg?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = {
                    if (onVerifyPin(pin)) {
                        pin = ""
                        onUnlocked()
                    } else {
                        pin = ""
                        errorMsg = "Incorrect PIN."
                    }
                },
                enabled = pin.length >= 4,
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Text("Unlock")
            }

            if (biometricEnabled) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { triggerBiometric() }) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use Biometrics")
                }
            }
        }
    }
}
