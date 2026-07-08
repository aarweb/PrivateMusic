package com.aar.privatemusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.player.PlayerController
import java.io.File

fun formatDuration(totalSec: Int): String {
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun ArtImage(model: Any?, size: androidx.compose.ui.unit.Dp) {
    Surface(
        modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.padding(size / 4),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    isCurrent: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            androidx.compose.material3.Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        ArtImage(song.artPath?.let { File(it) } ?: song.thumbnailUrl, 48.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = "Sonando",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).padding(end = 2.dp),
                    )
                }
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${song.artist} · ${formatDuration(song.durationSec)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!selectionMode) trailing()
    }
}

@Composable
fun MiniPlayer(controller: PlayerController, onOpenPlayer: () -> Unit) {
    val nowPlaying by controller.nowPlaying.collectAsState()
    val isPlaying by controller.isPlaying.collectAsState()
    val np = nowPlaying ?: return

    // Thin progress line: the most-checked info while browsing other tabs.
    var progress by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(isPlaying, np.songId) {
        while (true) {
            progress = if (np.durationMs > 0)
                (controller.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) else 0f
            kotlinx.coroutines.delay(1000)
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtImage(np.artPath?.let { File(it) }, 40.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        np.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { controller.togglePlayPause() }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pausa")
                }
                IconButton(onClick = { controller.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Siguiente")
                }
            }
        }
    }
}

/** Dialog listing existing playlists to add a song into, with inline creation. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onSelect: (Playlist) -> Unit,
    onCreateAndSelect: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var creating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var newName by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (creating) {
                TextButton(onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty()) onCreateAndSelect(trimmed)
                }) { Text("Crear y añadir") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Añadir a playlist") },
        text = {
            Column {
                if (creating) {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("Nombre de la playlist") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Spotify-style: creating a new list is the first option.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Nueva playlist…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    playlists.forEach { pl ->
                        Text(
                            pl.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(pl) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}
