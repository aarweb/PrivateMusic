package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.formatDuration
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

@Composable
fun PlaylistDetailScreen(app: PrivateMusicApp, playlistId: Long) {
    val songs by app.repository.observePlaylistSongs(playlistId).collectAsState(initial = emptyList())
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val reordered = songs.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        scope.launch { app.repository.reorderPlaylist(playlistId, reordered.map { it.id }) }
    }

    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Playlist vacía.\nAñade canciones desde la Biblioteca.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxSize()) {
        item(key = "header") {
            val totalMin = songs.sumOf { it.durationSec } / 60
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtImage(
                    playlist?.coverPath?.let { File(it) }
                        ?: songs.firstOrNull()?.artPath?.let { File(it) },
                    72.dp,
                )
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        playlist?.name ?: "Playlist",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${songs.size} canciones · $totalMin min",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                androidx.compose.material3.Button(
                    onClick = { app.playerController.playQueue(songs, 0) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Reproducir", Modifier.padding(start = 6.dp))
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = { app.playerController.playQueueShuffled(songs) },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Aleatorio", Modifier.padding(start = 6.dp))
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) { isDragging ->
                var menuOpen by remember { mutableStateOf(false) }
                val isCurrent = song.id == nowPlaying?.songId
                Surface(tonalElevation = if (isDragging) 4.dp else 0.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { app.playerController.playQueue(songs, index) }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtImage(song.artPath?.let { File(it) } ?: song.thumbnailUrl, 48.dp)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                song.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${song.artist} · ${formatDuration(song.durationSec)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Opciones")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Reproducir a continuación") },
                                    onClick = {
                                        menuOpen = false
                                        app.playerController.playNext(song)
                                        com.aar.privatemusic.util.Feedback.show("Sonará a continuación")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Añadir a la cola") },
                                    onClick = {
                                        menuOpen = false
                                        app.playerController.addToQueue(song)
                                        com.aar.privatemusic.util.Feedback.show("Añadida a la cola")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Quitar de la playlist") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { app.repository.removeFromPlaylist(playlistId, song.id) }
                                        com.aar.privatemusic.util.Feedback.show("Quitada de la playlist")
                                    },
                                )
                            }
                        }
                        IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                            Icon(Icons.Filled.DragHandle, contentDescription = "Reordenar")
                        }
                    }
                }
            }
        }
    }
}
