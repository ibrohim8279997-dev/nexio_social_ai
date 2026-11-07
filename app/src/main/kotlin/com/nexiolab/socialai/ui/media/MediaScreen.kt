package com.nexiolab.socialai.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nexiolab.socialai.AppContainer
import com.nexiolab.socialai.R
import com.nexiolab.socialai.domain.model.MediaFile
import com.nexiolab.socialai.domain.model.MediaKind
import com.nexiolab.socialai.ui.common.BaseViewModel
import com.nexiolab.socialai.ui.common.Format
import com.nexiolab.socialai.ui.common.Gap
import com.nexiolab.socialai.ui.common.Messages
import com.nexiolab.socialai.ui.common.appViewModel
import com.nexiolab.socialai.ui.components.EmptyState
import com.nexiolab.socialai.ui.theme.Nexio
import java.io.File

class MediaViewModel(private val app: AppContainer) : BaseViewModel() {
    val media = app.media.observeAll().state(emptyList())
    fun path(file: MediaFile) = File(app.media.absolutePath(file))
    fun import(uris: List<String>) = act(app.context.getString(R.string.common_media_added_to_library)) { uris.forEach { app.media.import(it) } }
    fun delete(file: MediaFile) = act(app.context.getString(R.string.common_deleted)) { app.media.delete(file) }
}

@Composable
fun MediaThumb(file: MediaFile, path: File, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Nexio.colors.cardHigh), contentAlignment = Alignment.Center) {
        when (file.kind) {
            MediaKind.IMAGE -> AsyncImage(path, file.displayName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            MediaKind.VIDEO -> Icon(Icons.Filled.Movie, stringResource(R.string.common_video), tint = Nexio.colors.textMuted, modifier = Modifier.size(32.dp))
            MediaKind.AUDIO -> Icon(Icons.Filled.AudioFile, stringResource(R.string.common_audio), tint = Nexio.colors.textMuted, modifier = Modifier.size(32.dp))
            MediaKind.DOCUMENT -> Icon(Icons.Filled.Description, stringResource(R.string.common_document), tint = Nexio.colors.textMuted, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun MediaScreen() {
    val vm = appViewModel { MediaViewModel(it) }
    Messages(vm)
    val media by vm.media.collectAsState()
    var pendingDelete by remember { mutableStateOf<MediaFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris.map { it.toString() })
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button({ picker.launch(arrayOf("image/*", "video/*", "audio/*", "application/pdf")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_add_files)) }
        }
        if (media.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Outlined.PermMedia, stringResource(R.string.common_no_media_yet), stringResource(R.string.common_media_empty_message))
            }
        }
        items(media, key = { it.id }) { file ->
            Column(Modifier.clickable { pendingDelete = file }) {
                MediaThumb(file, vm.path(file), Modifier.fillMaxWidth().aspectRatio(1f))
                Text(file.displayName, style = MaterialTheme.typography.labelSmall, color = Nexio.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text(Format.bytes(file.sizeBytes), style = MaterialTheme.typography.labelSmall, color = Nexio.colors.textMuted)
            }
        }
    }
    pendingDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(file.displayName) },
            text = {
                Column {
                    Text("${file.mimeType} · ${Format.bytes(file.sizeBytes)}")
                    Gap(4)
                    Text(stringResource(R.string.common_media_added_at, Format.dateTime(file.createdAt)))
                    Gap(4)
                    Text(stringResource(R.string.common_media_delete_question))
                }
            },
            confirmButton = { TextButton({ vm.delete(file); pendingDelete = null }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text(stringResource(R.string.common_keep)) } },
        )
    }
}
