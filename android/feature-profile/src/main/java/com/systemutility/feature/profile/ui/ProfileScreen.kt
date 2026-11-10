package com.systemutility.feature.profile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.systemutility.core.model.UserProfile
import com.systemutility.core.ui.theme.StatusGreen
import com.systemutility.feature.profile.ProfileUiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onAvatarPicked: (Uri) -> Unit,
    onRemoveAvatar: () -> Unit,
    onOpenChangePassword: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSafetyNumber: () -> Unit
) {
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAvatarPicked(uri)
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
                title = { Text("Profile & Identity", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
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
            state.statusMessage?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(msg, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.errorMessage?.let { err ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Self Profile Card
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val self = state.selfUser
                    Box(contentAlignment = Alignment.BottomEnd) {
                        ProfileAvatarCircle(profile = self, sizeDp = 96)
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(34.dp)
                                .clickable {
                                    avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Update photo", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    if (!self?.avatarR2Key.isNullOrBlank()) {
                        TextButton(onClick = onRemoveAvatar, enabled = !state.isSaving) {
                            Text("Remove profile photo", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(
                        text = "@${self?.username ?: "user"} • Slot ${self?.slotId ?: "-"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = state.draftName,
                        onValueChange = onNameChange,
                        label = { Text("Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.draftAbout,
                        onValueChange = onAboutChange,
                        label = { Text("About / Status") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onSaveProfile,
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Save Profile Changes")
                    }
                }
            }

            // Paired Partner Card (Always 1 partner in this 2-user architecture)
            state.partnerUser?.let { partner ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Paired Partner", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatarCircle(profile = partner, sizeDp = 56)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(partner.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("@${partner.username} • Slot ${partner.slotId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (partner.about.isNotBlank()) {
                                    Text(partner.about, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                            Icon(Icons.Default.Lock, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Account Security Actions
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column {
                    ListItem(
                        headlineContent = { Text("Safety Number Verification") },
                        supportingContent = { Text("Verify partner's Signal Protocol encryption fingerprint") },
                        leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenSafetyNumber() }
                    )
                    ListItem(
                        headlineContent = { Text("Change Password") },
                        supportingContent = { Text("Rotate Argon2id + server verifier and revoke other sessions") },
                        leadingContent = { Icon(Icons.Default.Password, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenChangePassword() }
                    )
                    ListItem(
                        headlineContent = { Text("Active Sessions & Devices") },
                        supportingContent = { Text("Inspect connected phones or revoke access") },
                        leadingContent = { Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { onOpenSessions() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatarCircle(profile: UserProfile?, sizeDp: Int) {
    val localPath = profile?.avatarLocalPath
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        if (localPath != null && File(localPath).exists()) {
            AsyncImage(
                model = File(localPath),
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = profile?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "U",
                fontSize = (sizeDp / 2.6f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
