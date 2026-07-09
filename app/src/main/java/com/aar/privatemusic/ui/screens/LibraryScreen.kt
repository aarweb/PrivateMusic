package com.aar.privatemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.ui.components.AddToPlaylistDialog
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.SongRow
import kotlinx.coroutines.launch
import java.io.File

/**
 * [chip] es el nombre corto: "Añadidas recientemente" no cabe en el chip y se
 * partía en tres líneas. En el menú sí hay sitio para el nombre largo.
 */
private enum class SortMode(val label: String, val chip: String) {
    RECENT("Añadidas recientemente", "Recientes"),
    TITLE("Título", "Título"),
    ARTIST("Artista", "Artista"),
    DURATION("Duración", "Duración"),
}

@Composable
fun LibraryScreen(app: PrivateMusicApp, onOpenArtist: (String) -> Unit = {}) {
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val recent by app.repository.observeRecentlyPlayed(10).collectAsState(initial = emptyList())
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val pendingDownloads by app.repository.observePendingDownloads().collectAsState(initial = emptyList())
    val downloadStates by app.downloader.downloads.collectAsState()
    // Downloads that failed (this session, or a stuck row from a past session):
    // shown in an errors section with a retry button. Active ones are excluded.
    val failedDownloads = pendingDownloads.filter { p ->
        when (val st = downloadStates[p.id]) {
            is com.aar.privatemusic.downloader.DownloadState.Failed -> true
            null -> p.attempts >= 1
            else -> false
        }
    }
    val scope = rememberCoroutineScope()

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForEdit by remember { mutableStateOf<Song?>(null) }
    var songForArt by remember { mutableStateOf<Song?>(null) }
    var songForAdventure by remember { mutableStateOf<Song?>(null) }
    var songForKaraoke by remember { mutableStateOf<Song?>(null) }
    var songForDelete by remember { mutableStateOf<Song?>(null) }
    var songForMetadata by remember { mutableStateOf<Song?>(null) }
    var query by remember { mutableStateOf("") }

    // Multi-select: long-press a row to enter, tap to toggle.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showBulkDelete by remember { mutableStateOf(false) }
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

    val context = LocalContext.current
    val artPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val song = songForArt
        songForArt = null
        if (uri != null && song != null) {
            scope.launch { app.repository.setSongArt(context, song, uri) }
        }
    }
    // Saveable: sort/filter survive tab switches (nav restoreState only keeps rememberSaveable).
    var sortMode by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.name },
            restore = { SortMode.valueOf(it) },
        )
    ) { mutableStateOf(SortMode.RECENT) }
    var onlyFavorites by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var artistView by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    val visibleSongs = remember(songs, query, sortMode, onlyFavorites) {
        songs
            .filter { !onlyFavorites || it.isFavorite }
            .filter {
                query.isBlank() ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true)
            }
            .let { list ->
                when (sortMode) {
                    SortMode.RECENT -> list.sortedByDescending { it.addedAt }
                    SortMode.TITLE -> list.sortedBy { it.title.lowercase() }
                    SortMode.ARTIST -> list.sortedBy { it.artist.lowercase() }
                    SortMode.DURATION -> list.sortedBy { it.durationSec }
                }
            }
    }

    if (songs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Tu biblioteca está vacía.\nDescarga música desde Buscar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onClose = { exitSelection() },
                onSelectAll = {
                    visibleSongs.forEach { if (it.id !in selectedIds) selectedIds.add(it.id) }
                },
                onAddToPlaylist = { showBulkPlaylist = true },
                onDelete = { showBulkDelete = true },
            )
        }
        if (!selectionMode) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Buscar en la biblioteca…") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Los chips se desplazan en vez de envolver: un chip partido en
                // tres líneas deforma toda la fila.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = onlyFavorites,
                onClick = { onlyFavorites = !onlyFavorites },
                label = { Text("Favoritas") },
                leadingIcon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
            )
            FilterChip(
                selected = artistView,
                onClick = { artistView = !artistView },
                label = { Text("Artistas") },
                leadingIcon = {
                    Icon(Icons.Filled.Person, contentDescription = null)
                },
            )
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                FilterChip(
                    selected = false,
                    onClick = { sortMenuOpen = true },
                    label = { Text(sortMode.chip, maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = "Ordenar") },
                )
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label) },
                            onClick = {
                                sortMode = mode
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }
        } // end if (!selectionMode)

        if (artistView && !selectionMode) {
            val artists = remember(songs, query) {
                songs
                    .filter { query.isBlank() || it.artist.contains(query, ignoreCase = true) }
                    .groupBy { it.artist.trim() }
                    .entries
                    .sortedByDescending { it.value.size }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(artists, key = { it.key }) { (artist, artistSongs) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenArtist(artist) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtImage(artistSongs.firstOrNull()?.artPath?.let { File(it) }, 48.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(
                                "${artistSongs.size} canciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (failedDownloads.isNotEmpty() && query.isBlank() && !selectionMode) {
                item(key = "dl-errors-header") {
                    Text(
                        "Descargas con error",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(failedDownloads, key = { "dlerr-${it.id}" }, contentType = { "dlerr" }) { p ->
                    val msg = (downloadStates[p.id] as? com.aar.privatemusic.downloader.DownloadState.Failed)?.message
                    DownloadErrorRow(
                        title = p.title,
                        thumbnailUrl = p.thumbnailUrl,
                        message = msg,
                        onRetry = { app.downloader.retry(p) },
                        onDismiss = { app.downloader.cancel(p.id) },
                    )
                }
            }
            if (recent.isNotEmpty() && query.isBlank() && !onlyFavorites && !selectionMode) {
                item(key = "recent-header") {
                    Text(
                        "Reproducidas recientemente",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                item(key = "recent-row") {
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(recent, key = { "recent-${it.id}" }) { song ->
                            Column(
                                modifier = Modifier
                                    .width(96.dp)
                                    .clickable {
                                        val index = recent.indexOfFirst { it.id == song.id }
                                        app.playerController.playQueue(recent, index)
                                    },
                            ) {
                                ArtImage(song.artPath?.let { File(it) } ?: song.thumbnailUrl, 96.dp)
                                Text(
                                    song.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                item(key = "all-header") {
                    Text(
                        "Todas las canciones",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(visibleSongs, key = { it.id }, contentType = { "song" }) { song ->
                var menuOpen by remember { mutableStateOf(false) }
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    selectionMode = selectionMode,
                    selected = song.id in selectedIds,
                    onLongClick = { startSelection(song.id) },
                    onClick = {
                        if (selectionMode) {
                            toggleSelect(song.id)
                        } else {
                            val index = visibleSongs.indexOfFirst { it.id == song.id }
                            app.playerController.playQueue(visibleSongs, index)
                        }
                    },
                    trailing = {
                        if (song.isFavorite) {
                            Icon(
                                Icons.Filled.Favorite,
                                contentDescription = "Favorita",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Opciones")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Radio de esta canción") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            val radio = app.repository.radioFor(song)
                                            if (radio.size > 1) {
                                                app.playerController.playQueue(radio, 0)
                                            } else {
                                                app.playerController.playQueue(listOf(song), 0)
                                            }
                                        }
                                    },
                                )
                                if (song.sonicFeatures != null) {
                                    DropdownMenuItem(
                                        text = { Text("Aventura sónica hasta…") },
                                        onClick = {
                                            menuOpen = false
                                            songForAdventure = song
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Karaoke (quitar la voz)") },
                                    onClick = {
                                        menuOpen = false
                                        songForKaraoke = song
                                    },
                                )
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
                                    text = { Text(if (song.isFavorite) "Quitar de favoritas" else "Añadir a favoritas") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch { app.repository.setFavorite(song.id, !song.isFavorite) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Añadir a playlist") },
                                    onClick = {
                                        menuOpen = false
                                        songForPlaylist = song
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Editar metadatos") },
                                    onClick = {
                                        menuOpen = false
                                        songForEdit = song
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Buscar metadatos (identificar)") },
                                    onClick = {
                                        menuOpen = false
                                        songForMetadata = song
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (song.snoozedUntil > System.currentTimeMillis())
                                                "Volver a sugerir en mixes"
                                            else "No sugerir en mixes (30 días)"
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            if (song.snoozedUntil > System.currentTimeMillis()) {
                                                app.repository.unsnoozeSong(song.id)
                                                com.aar.privatemusic.util.Feedback.show("Volverá a aparecer en tus mixes")
                                            } else {
                                                app.repository.snoozeSong(song.id)
                                                com.aar.privatemusic.util.Feedback.show("No se sugerirá durante 30 días")
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Cambiar carátula") },
                                    onClick = {
                                        menuOpen = false
                                        songForArt = song
                                        artPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Eliminar de la biblioteca") },
                                    onClick = {
                                        menuOpen = false
                                        songForDelete = song
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    songForPlaylist?.let { song ->
        AddToPlaylistDialog(
            playlists = playlists,
            onSelect = { pl ->
                scope.launch { app.repository.addToPlaylist(pl.id, song.id) }
                com.aar.privatemusic.util.Feedback.show("Añadida a \"${pl.name}\"")
                songForPlaylist = null
            },
            onCreateAndSelect = { name ->
                scope.launch {
                    val id = app.repository.createPlaylist(name)
                    app.repository.addToPlaylist(id, song.id)
                }
                com.aar.privatemusic.util.Feedback.show("Creada \"$name\" con la canción")
                songForPlaylist = null
            },
            onDismiss = { songForPlaylist = null },
        )
    }

    songForEdit?.let { song ->
        var title by remember(song.id) { mutableStateOf(song.title) }
        var artist by remember(song.id) { mutableStateOf(song.artist) }
        AlertDialog(
            onDismissRequest = { songForEdit = null },
            title = { Text("Editar metadatos") },
            text = {
                Column {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Título") },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = artist,
                        onValueChange = { artist = it },
                        label = { Text("Artista") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch { app.repository.updateSongMeta(song.id, title, artist) }
                    }
                    songForEdit = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { songForEdit = null }) { Text("Cancelar") } },
        )
    }

    songForKaraoke?.let { song ->
        KaraokeDialog(app, song, onDismiss = { songForKaraoke = null })
    }

    songForDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songForDelete = null },
            title = { Text("¿Eliminar canción?") },
            text = {
                Text(
                    if (song.id.startsWith("local_"))
                        "“${song.title}” se quitará de la biblioteca. El archivo original del dispositivo NO se borra."
                    else "“${song.title}” y su archivo de audio se borrarán del dispositivo. Esta acción no se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.repository.deleteSong(song) }
                    songForDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { songForDelete = null }) { Text("Cancelar") } },
        )
    }

    songForMetadata?.let { song ->
        MetadataIdentifyDialog(app = app, song = song, onDismiss = { songForMetadata = null })
    }

    if (showBulkDelete) {
        val toDelete = songs.filter { it.id in selectedIds }
        val anyRemote = toDelete.any { !it.id.startsWith("local_") }
        AlertDialog(
            onDismissRequest = { showBulkDelete = false },
            title = {
                Text(
                    if (toDelete.size == 1) "¿Eliminar 1 canción?"
                    else "¿Eliminar ${toDelete.size} canciones?"
                )
            },
            text = {
                Text(
                    if (anyRemote)
                        "Se borrarán de la biblioteca y sus archivos de audio del dispositivo. Esta acción no se puede deshacer."
                    else "Se quitarán de la biblioteca. Los archivos originales del dispositivo NO se borran."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDelete = false
                    val n = toDelete.size
                    scope.launch { app.repository.deleteSongs(toDelete) }
                    com.aar.privatemusic.util.Feedback.show(if (n == 1) "1 eliminada" else "$n eliminadas")
                    exitSelection()
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBulkDelete = false }) { Text("Cancelar") } },
        )
    }

    if (showBulkPlaylist) {
        val ids = selectedIds.toList()
        AddToPlaylistDialog(
            playlists = playlists,
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

    songForAdventure?.let { from ->
        SonicAdventureDialog(
            from = from,
            candidates = songs,
            onPick = { to ->
                songForAdventure = null
                scope.launch {
                    val journey = app.repository.sonicAdventure(from, to)
                    if (journey.size > 1) app.playerController.playQueue(journey, 0)
                    else app.playerController.playQueue(listOf(from, to), 0)
                }
            },
            onDismiss = { songForAdventure = null },
        )
    }
}

/** Picks the destination song for a Sonic Adventure starting from [from]. */
@Composable
private fun SonicAdventureDialog(
    from: Song,
    candidates: List<Song>,
    onPick: (Song) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val options = remember(candidates, query, from.id) {
        candidates
            .filter { it.id != from.id && it.sonicFeatures != null }
            .filter {
                query.isBlank() ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.artist.contains(query, ignoreCase = true)
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aventura sónica") },
        text = {
            Column {
                Text(
                    "Un viaje que transforma gradualmente “${from.title}” en la canción que elijas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Buscar destino") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(options, key = { it.id }) { song ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(song) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                song.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Contextual action bar shown while selecting songs in the library. */
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Cerrar selección")
            }
            Text(
                if (count == 1) "1 seleccionada" else "$count seleccionadas",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.DoneAll, contentDescription = "Seleccionar todo")
            }
            IconButton(onClick = onAddToPlaylist, enabled = count > 0) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "Añadir a playlist")
            }
            IconButton(onClick = onDelete, enabled = count > 0) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DownloadErrorRow(
    title: String,
    thumbnailUrl: String,
    message: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(thumbnailUrl, 48.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                message?.take(90) ?: "No se pudo descargar",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        IconButton(onClick = onRetry) {
            Icon(Icons.Filled.Refresh, contentDescription = "Reintentar")
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Descartar")
        }
    }
}

@Composable
private fun MetadataIdentifyDialog(
    app: PrivateMusicApp,
    song: Song,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<com.aar.privatemusic.metadata.MatchResult?>(null) }
    var applying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(song.id) {
        result = runCatching { app.metadataService.identify(song) }.getOrNull()
        loading = false
    }
    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text("Identificar canción", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            when {
                loading || applying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (applying) "Aplicando…" else "Buscando en iTunes, Deezer y MusicBrainz…")
                }
                result == null || result!!.candidates.isEmpty() ->
                    Text("No se encontraron coincidencias. Edita el título manualmente y vuelve a intentarlo.")
                else -> Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    result!!.candidates.take(6).forEachIndexed { i, m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !applying) {
                                    applying = true
                                    scope.launch {
                                        app.metadataService.apply(song, m)
                                        com.aar.privatemusic.util.Feedback.show("Actualizada: ${m.artist} - ${m.title}")
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ArtImage(m.artworkUrl, 48.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    m.title + if (i == 0 && result!!.confident) "  ·  recomendado" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    listOfNotNull(
                                        m.artist.ifBlank { null },
                                        m.album,
                                        m.year?.toString(),
                                    ).joinToString(" · "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (!applying) onDismiss() }) { Text("Cerrar") } },
    )
}
