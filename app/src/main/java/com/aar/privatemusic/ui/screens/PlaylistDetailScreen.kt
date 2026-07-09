package com.aar.privatemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.AddToPlaylistDialog
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.PlaylistCover
import com.aar.privatemusic.ui.components.SongRow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File

/**
 * Orden de VISTA de una playlist. Es no destructivo: [MANUAL] sirve el orden
 * guardado en `position` y el resto sólo reordena en memoria, así que el orden
 * manual del usuario sobrevive a mirar la lista por artista o por duración.
 */
private enum class PlaylistSort(val label: String) {
    MANUAL("Manual"),
    TITLE("Título"),
    ARTIST("Artista"),
    ALBUM("Álbum"),
    ADDED("Más recientes"),
    DURATION("Duración"),
    MOST_PLAYED("Más escuchadas"),
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlaylistDetailScreen(app: PrivateMusicApp, playlistId: Long) {
    // `null` = todavía no ha llegado la primera emisión. Con emptyList() la
    // pantalla parpadeaba "Playlist vacía" antes de pintar las canciones.
    val loadedSongs by app.repository.observePlaylistSongs(playlistId).collectAsState(initial = null)
    val allSongs = loadedSongs ?: emptyList()
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    var sort by remember { mutableStateOf(PlaylistSort.MANUAL) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    // Conteos sólo cuando hacen falta: es una consulta, no un Flow.
    var playCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(sort) {
        if (sort == PlaylistSort.MOST_PLAYED && playCounts.isEmpty()) {
            playCounts = runCatching { app.repository.playCounts() }.getOrDefault(emptyMap())
        }
    }

    val songs = remember(allSongs, sort, query, playCounts) {
        val sorted = when (sort) {
            PlaylistSort.MANUAL -> allSongs
            PlaylistSort.TITLE -> allSongs.sortedBy { it.title.lowercase() }
            PlaylistSort.ARTIST -> allSongs.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            PlaylistSort.ALBUM -> allSongs.sortedWith(compareBy({ it.album?.lowercase() ?: "￿" }, { it.trackNumber ?: 0 }))
            PlaylistSort.ADDED -> allSongs.sortedByDescending { it.addedAt }
            PlaylistSort.DURATION -> allSongs.sortedByDescending { it.durationSec }
            PlaylistSort.MOST_PLAYED -> allSongs.sortedByDescending { playCounts[it.id] ?: 0 }
        }
        if (query.isBlank()) sorted else sorted.filter { s ->
            val q = query.trim().lowercase()
            s.title.lowercase().contains(q) || s.artist.lowercase().contains(q) ||
                s.album?.lowercase()?.contains(q) == true
        }
    }

    // Arrastrar sólo tiene sentido con el orden guardado y sin filtrar: en
    // cualquier otra vista los índices de pantalla no son los de `position`,
    // y reordenar corrompería el orden manual.
    val canReorder = sort == PlaylistSort.MANUAL && query.isBlank() && !searching

    // Multi-select: long-press a row to enter, tap to toggle.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showBulkPlaylist by remember { mutableStateOf(false) }
    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
    }
    fun toggleSelect(id: String) {
        if (!selectedIds.remove(id)) selectedIds.add(id)
        if (selectedIds.isEmpty()) selectionMode = false
    }
    fun startSelection(id: String) {
        selectionMode = true
        if (id !in selectedIds) selectedIds.add(id)
    }
    BackHandler(enabled = selectionMode) { exitSelection() }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Por clave y no por índice: los índices de la lista perezosa cuentan
        // también la cabecera, así que usarlos movía la canción de al lado.
        val fromIdx = allSongs.indexOfFirst { it.id == from.key }
        val toIdx = allSongs.indexOfFirst { it.id == to.key }
        if (canReorder && fromIdx >= 0 && toIdx >= 0) {
            val reordered = allSongs.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
            scope.launch { app.repository.reorderPlaylist(playlistId, reordered.map { it.id }) }
        }
    }

    if (loadedSongs == null) return // cargando: nada mejor que el hueco de un instante
    if (allSongs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            com.aar.privatemusic.ui.components.EmptyState(
                "Playlist vacía.\nAñade canciones desde la Biblioteca.",
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
    if (selectionMode) {
        Surface(tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { exitSelection() }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cerrar selección")
                }
                Text(
                    if (selectedIds.size == 1) "1 seleccionada" else "${selectedIds.size} seleccionadas",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = {
                    songs.forEach { if (it.id !in selectedIds) selectedIds.add(it.id) }
                }) { Icon(Icons.Filled.DoneAll, contentDescription = "Seleccionar todo") }
                IconButton(onClick = { showBulkPlaylist = true }, enabled = selectedIds.isNotEmpty()) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "Añadir a otra playlist")
                }
                IconButton(
                    onClick = {
                        val ids = selectedIds.toList()
                        scope.launch { ids.forEach { app.repository.removeFromPlaylist(playlistId, it) } }
                        com.aar.privatemusic.util.Feedback.show(
                            if (ids.size == 1) "1 quitada de la playlist" else "${ids.size} quitadas de la playlist"
                        )
                        exitSelection()
                    },
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.PlaylistRemove,
                        contentDescription = "Quitar de la playlist",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    LazyColumn(state = lazyListState, modifier = Modifier.weight(1f).fillMaxWidth()) {
        item(key = "header") {
            val totalMin = allSongs.sumOf { it.durationSec } / 60
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val art = remember(allSongs) {
                    allSongs.mapNotNull { it.artPath }.distinct().take(4)
                }
                PlaylistCover(playlist?.coverPath, art, 72.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        playlist?.name ?: "Playlist",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    playlist?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        // Al filtrar, el contador de la playlist entera engañaría.
                        if (songs.size != allSongs.size) "${songs.size} de ${allSongs.size} canciones"
                        else "${allSongs.size} ${if (allSongs.size == 1) "canción" else "canciones"} · $totalMin min",
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
                    onClick = {
                        // Aleatorio que no repite lo de ayer (ver shuffleFewerRepeats).
                        scope.launch {
                            app.playerController.playQueueInOrder(
                                app.repository.shuffleFewerRepeats(songs),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Aleatorio", Modifier.padding(start = 6.dp))
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searching) {
                    androidx.compose.material3.OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Buscar en la playlist") },
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    )
                    IconButton(onClick = { searching = false; query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar búsqueda")
                    }
                } else {
                    IconButton(onClick = { searching = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Buscar en la playlist")
                    }
                    Box {
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                            Text(sort.label, Modifier.padding(start = 6.dp))
                        }
                        DropdownMenu(sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            PlaylistSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = { sort = option; sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (sort != PlaylistSort.MANUAL) {
                        Text(
                            "orden de vista",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { index, song ->
            ReorderableItem(reorderableState, key = song.id) { isDragging ->
                var menuOpen by remember { mutableStateOf(false) }
                // La misma SongRow que la Biblioteca: el asa de arrastre entra
                // por `trailing`, que se compone dentro del ámbito reordenable.
                Surface(tonalElevation = if (isDragging) 4.dp else 0.dp) {
                    SongRow(
                        song = song,
                        isCurrent = song.id == nowPlaying?.songId,
                        selectionMode = selectionMode,
                        selected = song.id in selectedIds,
                        onClick = {
                            if (selectionMode) toggleSelect(song.id)
                            else app.playerController.playQueue(songs, index)
                        },
                        onLongClick = { startSelection(song.id) },
                        onSwipeToQueue = {
                            app.playerController.addToQueue(song)
                            com.aar.privatemusic.util.Feedback.show("Añadida a la cola")
                        },
                        trailing = {
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
                            if (canReorder) {
                                IconButton(onClick = {}, modifier = Modifier.draggableHandle()) {
                                    Icon(Icons.Filled.DragHandle, contentDescription = "Reordenar")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
    }

    if (showBulkPlaylist) {
        val ids = selectedIds.toList()
        AddToPlaylistDialog(
            playlists = playlists.filter { it.id != playlistId },
            onSelect = { pl ->
                showBulkPlaylist = false
                scope.launch {
                    val added = app.repository.addSongsToPlaylist(pl.id, ids)
                    val skipped = ids.size - added
                    com.aar.privatemusic.util.Feedback.show(
                        if (skipped > 0) "Añadidas $added a \"${pl.name}\" ($skipped ya estaban)"
                        else "Añadidas $added a \"${pl.name}\""
                    )
                }
                exitSelection()
            },
            onCreateAndSelect = { name ->
                showBulkPlaylist = false
                scope.launch {
                    val id = app.repository.createPlaylist(name)
                    app.repository.addSongsToPlaylist(id, ids)
                    com.aar.privatemusic.util.Feedback.show("Creada \"$name\" con ${ids.size} canciones")
                }
                exitSelection()
            },
            onDismiss = { showBulkPlaylist = false },
        )
    }
}
