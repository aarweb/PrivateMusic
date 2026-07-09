package com.aar.privatemusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * Portada de una playlist: la imagen elegida a mano si la hay; si no, un mosaico
 * con las carátulas de sus primeras canciones. Con menos de cuatro carátulas
 * distintas el mosaico quedaría cojo, así que se usa la primera a pantalla
 * completa, y sin ninguna, el icono de siempre.
 */
@Composable
fun PlaylistCover(
    coverPath: String?,
    artPaths: List<String>,
    size: androidx.compose.ui.unit.Dp,
) {
    when {
        coverPath != null -> ArtImage(File(coverPath), size)
        artPaths.size >= 4 -> Surface(
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column {
                for (row in 0 until 2) {
                    Row(Modifier.weight(1f)) {
                        for (col in 0 until 2) {
                            AsyncImage(
                                model = File(artPaths[row * 2 + col]),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
        artPaths.isNotEmpty() -> ArtImage(File(artPaths.first()), size)
        else -> Surface(
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                modifier = Modifier.padding(size / 4),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Una frase tranquila en el centro, igual en todas las pantallas. Antes cada una
 * escribía su propio hueco con su propio estilo.
 */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
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
    onSwipeToQueue: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    // Deslizar hacia la derecha encola la canción. La fila NO se va: vuelve a su
    // sitio (confirmValueChange devuelve false), porque encolar no la quita de
    // ninguna parte. Se desactiva durante la selección múltiple, donde el gesto
    // competiría con las casillas.
    if (onSwipeToQueue != null && !selectionMode) {
        val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) onSwipeToQueue()
                false
            },
        )
        androidx.compose.material3.SwipeToDismissBox(
            state = state,
            enableDismissFromEndToStart = false,
            backgroundContent = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "A la cola",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            },
        ) {
            // Opaca a propósito: la fila es transparente y dejaría ver el fondo
            // verde deslizándose por debajo.
            Surface(color = MaterialTheme.colorScheme.surface) {
                SongRowContent(song, onClick, isCurrent, selectionMode, selected, onLongClick, trailing)
            }
        }
        return
    }
    SongRowContent(song, onClick, isCurrent, selectionMode, selected, onLongClick, trailing)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRowContent(
    song: Song,
    onClick: () -> Unit,
    isCurrent: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onLongClick: (() -> Unit)?,
    trailing: @Composable () -> Unit,
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
fun MiniPlayer(
    controller: PlayerController,
    onOpenPlayer: () -> Unit,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
    /** Ancla del elemento compartido: la carátula vuela hasta el reproductor. */
    coverModifier: Modifier = Modifier,
) {
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
            // Deslizar sobre la fila cambia de pista, como en cualquier
            // reproductor: el umbral evita que un desplazamiento vertical
            // torcido salte de canción sin querer.
            var dragged by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPlayer)
                    .pointerInput(np.songId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragged <= -80f) controller.next()
                                else if (dragged >= 80f) controller.previous()
                                dragged = 0f
                            },
                            onDragCancel = { dragged = 0f },
                        ) { _, amount -> dragged += amount }
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box(coverModifier) {
                    ArtImage(np.artPath?.let { File(it) }, 40.dp)
                }
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
                if (onToggleFavorite != null) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Quitar de favoritas" else "Marcar como favorita",
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
