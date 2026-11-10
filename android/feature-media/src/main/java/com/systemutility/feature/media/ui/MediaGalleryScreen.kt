package com.systemutility.feature.media.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.systemutility.core.database.entity.AttachmentEntity
import com.systemutility.feature.media.MediaGalleryUiState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaGalleryScreen(
    state: MediaGalleryUiState,
    onBackClick: () -> Unit,
    onDownloadClick: (AttachmentEntity) -> Unit,
    onSaveToDownloadsClick: (AttachmentEntity) -> Unit,
    onDeleteLocalClick: (String) -> Unit,
    onClearStatus: () -> Unit
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var previewImage by remember { mutableStateOf<AttachmentEntity?>(null) }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbar.showSnackbar(it)
            onClearStatus()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Encrypted Media & Docs") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Media (${state.visualMedia.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Docs & Audio (${state.docsAndAudio.size})") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Links (${state.links.size})") })
            }

            when (selectedTab) {
                0 -> {
                    if (state.visualMedia.isEmpty()) {
                        EmptyGalleryState("No photos or videos shared yet.")
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.visualMedia, key = { it.id }) { item ->
                                val previewFile = item.localPlainPath ?: item.thumbnailPath
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            if (item.localPlainPath == null) {
                                                onDownloadClick(item)
                                            } else {
                                                previewImage = item
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (previewFile != null && File(previewFile).exists()) {
                                        AsyncImage(
                                            model = File(previewFile),
                                            contentDescription = item.fileName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    if (item.category == "video") {
                                        Icon(
                                            Icons.Default.PlayCircleOutline,
                                            contentDescription = "Video",
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else if (item.localPlainPath == null) {
                                        Icon(
                                            Icons.Default.CloudDownload,
                                            contentDescription = "Decrypt & Download",
                                            tint = Color.White,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }
                                    if (item.transferState == "DOWNLOADING" || item.transferState == "UPLOADING") {
                                        LinearProgressIndicator(
                                            progress = { item.progressPercent / 100f },
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (state.docsAndAudio.isEmpty()) {
                        EmptyGalleryState("No documents or voice/audio files yet.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(state.docsAndAudio, key = { it.id }) { doc ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (doc.category == "voice" || doc.category == "audio") Icons.Default.AudioFile else Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(doc.fileName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${formatFileSize(doc.sizeBytes)} • ${doc.transferState}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (doc.localPlainPath == null) {
                                            IconButton(onClick = { onDownloadClick(doc) }) {
                                                Icon(Icons.Default.CloudDownload, contentDescription = "Download")
                                            }
                                        } else {
                                            IconButton(onClick = { onSaveToDownloadsClick(doc) }) {
                                                Icon(Icons.Default.Download, contentDescription = "Save to Downloads")
                                            }
                                            IconButton(onClick = { onDeleteLocalClick(doc.id) }) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove local cache")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    if (state.links.isEmpty()) {
                        EmptyGalleryState("No links shared yet.")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.links) { item ->
                                ListItem(
                                    leadingContent = { Icon(Icons.Default.Link, contentDescription = null) },
                                    headlineContent = { Text(item.url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    modifier = Modifier.clickable {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url)))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    previewImage?.let { item ->
        val plainPath = item.localPlainPath
        AlertDialog(
            onDismissRequest = { previewImage = null },
            title = { Text(item.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                if (plainPath != null && item.category == "image") {
                    AsyncImage(
                        model = File(plainPath),
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    )
                } else {
                    Text("Decrypted file ready (${formatFileSize(item.sizeBytes)}).")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSaveToDownloadsClick(item)
                    previewImage = null
                }) {
                    Text("Save to Downloads")
                }
            },
            dismissButton = {
                TextButton(onClick = { previewImage = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun EmptyGalleryState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.1f MB".format(kb / 1024.0)
}
