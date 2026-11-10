package com.systemutility.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.settings.update.AppUpdateUiState
import com.systemutility.feature.settings.update.UpdatePhase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdatesScreen(
    updateState: AppUpdateUiState,
    onBackClick: () -> Unit,
    onSaveRepo: (String) -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallDownloaded: () -> Unit,
    onRequestInstallPermission: () -> Unit,
    onClearNotices: () -> Unit
) {
    var repoDraft by remember(updateState.configuredRepo) {
        mutableStateOf(updateState.configuredRepo.ifBlank { updateState.defaultRepo })
    }

    LaunchedEffect(updateState.configuredRepo, updateState.defaultRepo) {
        if (repoDraft.isBlank()) {
            repoDraft = updateState.configuredRepo.ifBlank { updateState.defaultRepo }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("App Updates (Git Releases)", fontWeight = FontWeight.Bold) }
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
            updateState.statusMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearNotices) { Text("OK") }
                    }
                }
            }

            updateState.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onClearNotices) { Text("OK") }
                    }
                }
            }

            // Installed Version Summary
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Installed Version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "Version ${updateState.installedVersionName} (Build ${updateState.installedVersionCode})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (updateState.lastCheckedAt > 0L) {
                        Text(
                            text = "Last checked: ${SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(updateState.lastCheckedAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = onCheckNow,
                        enabled = updateState.phase != UpdatePhase.CHECKING && updateState.phase != UpdatePhase.DOWNLOADING,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (updateState.phase == UpdatePhase.CHECKING) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking GitHub Releases…")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check for Updates")
                        }
                    }
                }
            }

            // Active Available Release Card
            updateState.availableRelease?.let { rel ->
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = StatusGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Update Available: ${rel.releaseName}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = "Tag: ${rel.tagName} • Asset: ${rel.apkName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (rel.expectedSha256Hex != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SHA-256 verified (${rel.expectedSha256Hex!!.take(12)}…)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusGreen
                                )
                            }
                        }
                        if (rel.changelog.isNotBlank()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = rel.changelog.trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        when (updateState.phase) {
                            UpdatePhase.DOWNLOADING -> {
                                LinearProgressIndicator(
                                    progress = { updateState.downloadProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Downloading ${updateState.downloadProgress}%…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            UpdatePhase.NEEDS_INSTALL_PERMISSION -> {
                                Text(
                                    text = "APK verified & ready. Grant \"Install unknown apps\" permission to complete installation.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Button(
                                    onClick = onRequestInstallPermission,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Allow & Install ${rel.tagName}")
                                }
                            }

                            UpdatePhase.READY_TO_INSTALL -> {
                                Button(
                                    onClick = onInstallDownloaded,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Install ${rel.tagName}")
                                }
                            }

                            else -> {
                                Button(
                                    onClick = onDownloadAndInstall,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download & Install ${rel.tagName}")
                                }
                            }
                        }
                    }
                }
            }

            // GitHub Repository Configuration
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GitHub Releases Source", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Enter the public GitHub repository (owner/repo) publishing your signed release APKs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = repoDraft,
                        onValueChange = { repoDraft = it },
                        label = { Text("GitHub Repository (owner/repo)") },
                        placeholder = { Text("your-username/system-utility") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { onSaveRepo(repoDraft) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save Repository", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    ListItem(
                        headlineContent = { Text("Automatic Update Checks") },
                        supportingContent = { Text("Check GitHub Releases automatically on startup (6h cooldown)") },
                        trailingContent = {
                            Switch(
                                checked = updateState.autoCheckEnabled,
                                onCheckedChange = onToggleAutoCheck
                            )
                        }
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Data Persistence: Updating via GitHub Releases preserves your encrypted SQLCipher chat history, attachments, preferences, and Signal keys in place (signed with the same release key).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
fun UpdateAvailablePromptDialog(
    updateState: AppUpdateUiState,
    onDismissLater: () -> Unit,
    onSkipRelease: () -> Unit,
    onOpenUpdates: () -> Unit,
    onDownloadNow: () -> Unit
) {
    val rel = updateState.availableRelease ?: return
    if (!updateState.shouldShowPromptDialog) return

    AlertDialog(
        onDismissRequest = onDismissLater,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Update Available (${rel.tagName})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "A newer release of System Utility is available (installed v${updateState.installedVersionName}). Your local chat history and keys remain preserved.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (rel.changelog.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = rel.changelog.trim().take(400),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDownloadNow()
                onOpenUpdates()
            }) {
                Text("Download & Install")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkipRelease) { Text("Skip") }
                TextButton(onClick = onDismissLater) { Text("Later") }
            }
        }
    )
}
