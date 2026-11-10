package com.systemutility.feature.auth.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.systemutility.core.ui.components.EmptyStateView
import com.systemutility.core.ui.components.ErrorBanner
import com.systemutility.core.ui.components.LoadingView
import com.systemutility.core.ui.theme.StatusAmber
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.core.ui.theme.StatusRed
import com.systemutility.feature.auth.SessionsUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onRefresh: () -> Unit,
    onRevokeSession: (String) -> Unit,
    onRevokeDevice: (String) -> Unit,
    onApproveDevice: (String) -> Unit = {},
    onRejectDevice: (String) -> Unit = {},
    onBackClick: () -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Devices & Sessions", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let { ErrorBanner(message = it) }
            state.statusMessage?.let { msg ->
                Surface(
                    color = StatusGreen.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(msg, color = StatusGreen, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
                }
            }

            when {
                state.isLoading -> LoadingView("Loading registered sessions…")
                state.sessions.isEmpty() && state.pendingDevices.isEmpty() -> EmptyStateView(
                    icon = Icons.Default.Security,
                    title = "No Active Sessions",
                    subtitle = "No active authenticated sessions were returned by the server."
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.pendingDevices.isNotEmpty()) {
                        item(key = "pending_header") {
                            Text(
                                text = "Pending Device Approvals",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusAmber
                            )
                        }
                        items(state.pendingDevices, key = { "pending_${it.deviceId}" }) { pending ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = StatusAmber.copy(alpha = 0.08f))
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.PendingActions, contentDescription = null, tint = StatusAmber)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(pending.deviceName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "${pending.platform} • ID ${pending.deviceId.take(8)}… • Requested ${formatSessionTime(pending.createdAt)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(onClick = { onApproveDevice(pending.deviceId) }) {
                                            Text("Approve Device")
                                        }
                                        OutlinedButton(
                                            onClick = { onRejectDevice(pending.deviceId) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                                        ) {
                                            Text("Reject")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.sessions.isNotEmpty()) {
                        item(key = "active_header") {
                            Text(
                                text = "Authorized Active Sessions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(state.sessions, key = { it.sessionId }) { session ->
                        val isCurrent = session.sessionId == state.currentSessionId
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(session.deviceName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                                            Text("Signal Device #${session.signalDeviceId} • ${session.platform}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    if (isCurrent) {
                                        Surface(color = StatusGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                                            Text("This Device", color = StatusGreen, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Last active: ${formatSessionTime(session.lastUsedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = { onRevokeSession(session.sessionId) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                                    ) {
                                        Text(if (isCurrent) "Sign Out Here" else "Revoke Session")
                                    }
                                    if (!isCurrent) {
                                        OutlinedButton(
                                            onClick = { onRevokeDevice(session.deviceId) },
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRed)
                                        ) {
                                            Text("Revoke Device")
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
}

private fun formatSessionTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(millis))
