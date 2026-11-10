package com.systemutility.feature.backup.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.systemutility.core.database.entity.BackupMetaEntity
import com.systemutility.core.model.FeatureFlags
import com.systemutility.core.ui.theme.StatusAmber
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.backup.BackupArchiveCodec
import com.systemutility.feature.backup.BackupUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    state: BackupUiState,
    onBackClick: () -> Unit,
    onImportRecoveryKey: (String) -> Unit,
    onCreateLocalSnapshot: () -> Unit,
    onRestoreLocalSnapshot: (BackupMetaEntity) -> Unit,
    onClearNotices: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var revealKey by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importKeyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (FeatureFlags.BACKUP_ENABLED) "Backup & Restore" else "Backup & Restore", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (!FeatureFlags.BACKUP_ENABLED) {
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
                            text = "Backup & Restore",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Automated encrypted backup and Google Drive sync will unlock in a future release. Your local SQLCipher messages, media files, and Signal keys stay persistently saved on this device across restarts and Git Release updates.",
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.statusMessage?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearNotices) { Text("OK") }
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearNotices) { Text("OK") }
                    }
                }
            }

            // Future Cloud Backup Placeholder Banner
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.12f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, tint = StatusAmber)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google Drive Backup Deferred",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = StatusAmber
                        )
                    }
                    Text(
                        text = "Cloud backup via Google Drive is deferred for a future release, and no Google OAuth permissions are requested. Note that Cloudflare D1/R2 stores encrypted relay objects only and is NOT a complete backup strategy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Independent Backup Recovery Key Card
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, null, tint = StatusGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Independent Backup Recovery Key", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Local encrypted snapshots use AES-256-GCM with this 256-bit key. Store it safely offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (revealKey) BackupArchiveCodec.formatDisplayKey(state.recoveryKeyHex)
                                else "•••• •••• •••• •••• •••• •••• •••• ••••",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { revealKey = !revealKey }) {
                                Icon(if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle key")
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(state.recoveryKeyHex)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy key")
                            }
                        }
                    }
                    OutlinedButton(onClick = {
                        importKeyInput = ""
                        showImportDialog = true
                    }) {
                        Text("Import Existing Recovery Key")
                    }
                }
            }

            // Local Encrypted Snapshot Placeholder Controls
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Local Encrypted Snapshots", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Creates an encrypted AES-256-GCM archive in app-private storage using the future-compatible backup codec.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onCreateLocalSnapshot,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Local Encrypted Snapshot")
                    }
                }
            }

            // Local Snapshot History
            Text("Local Snapshot Archives", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state.localHistory.isEmpty()) {
                Text(
                    text = "No local encrypted snapshots created on this device yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column {
                        state.localHistory.take(10).forEach { rec ->
                            val isLocal = rec.driveFileId.startsWith("local:")
                            ListItem(
                                headlineContent = { Text("${rec.versionTag} • ${rec.status}", fontWeight = FontWeight.Medium) },
                                supportingContent = {
                                    val detail = rec.errorDetail ?: "SHA-256 ${rec.sha256CiphertextHex.take(16)}… • ${(rec.sizeBytes / 1024L).coerceAtLeast(1L)} KB"
                                    Text("${formatBackupDate(rec.createdAt)} • $detail", fontSize = 12.sp)
                                },
                                trailingContent = {
                                    if (isLocal) {
                                        IconButton(onClick = { onRestoreLocalSnapshot(rec) }, enabled = !state.isBusy) {
                                            Icon(Icons.Default.Restore, contentDescription = "Restore Snapshot", tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Enter Backup Recovery Key") },
            text = {
                Column {
                    Text("Paste your 64-character hex recovery key to decrypt local encrypted snapshots.")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = importKeyInput,
                        onValueChange = { importKeyInput = it },
                        label = { Text("64-char hex key") },
                        singleLine = false,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onImportRecoveryKey(importKeyInput)
                    showImportDialog = false
                }) { Text("Save Key") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatBackupDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(ms))
