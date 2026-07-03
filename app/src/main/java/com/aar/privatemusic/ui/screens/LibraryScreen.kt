package com.aar.privatemusic.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
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

private enum class SortMode(val label: String) {
    RECENT("Añadidas recientemente"),
    TITLE("Título"),
    ARTIST("Artista"),
    DURATION("Duración"),
}

@Composable
fun LibraryScreen(app: PrivateMusicApp) {
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val recent by app.repository.observeRecentlyPlayed(10).collectAsState(initial = emptyList())
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var songForPlaylist by remember { mutableStateOf<Song?>(null) }
    var songForEdit by remember { mutableStateOf<Song?>(null) }
    var songForArt by remember { mutableStateOf<Song?>(null) }
    var query by remember { mutableStateOf("") }

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
    var sortMode by remember { mutableStateOf(SortMode.RECENT) }
    var onlyFavorites by remember { mutableStateOf(false) }

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
            Box {
                var sortMenuOpen by remember { mutableStateOf(false) }
                FilterChip(
                    selected = false,
                    onClick = { sortMenuOpen = true },
                    label = { Text(sortMode.label) },
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

        LazyColumn(Modifier.fillMaxSize()) {
            if (recent.isNotEmpty() && query.isBlank() && !onlyFavorites) {
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

            items(visibleSongs, key = { it.id }) { song ->
                var menuOpen by remember { mutableStateOf(false) }
                SongRow(
                    song = song,
                    onClick = {
                        val index = visibleSongs.indexOfFirst { it.id == song.id }
                        app.playerController.playQueue(visibleSongs, index)
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
                                DropdownMenuItem(
                                    text = { Text("Reproducir a continuación") },
                                    onClick = {
                                        menuOpen = false
                                        app.playerController.playNext(song)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Añadir a la cola") },
                                    onClick = {
                                        menuOpen = false
                                        app.playerController.addToQueue(song)
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
                                            if (song.snoozedUntil > System.currentTimeMillis())
                                                app.repository.unsnoozeSong(song.id)
                                            else app.repository.snoozeSong(song.id)
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
                                        scope.launch { app.repository.deleteSong(song) }
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
}
