package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.MusicDao
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.DesktopStorage
import com.aar.privatemusic.desktop.player.DesktopPlayer
import com.aar.privatemusic.desktop.update.DesktopUpdater

// ---- Inicio ----

@Composable
fun HomeScreen(songs: List<Song>, player: DesktopPlayer, onSync: () -> Unit, syncing: Boolean) {
    if (songs.isEmpty()) {
        EmptyState(
            title = "Todavía no hay música",
            subtitle = "Trae tu biblioteca del móvil, o copia canciones a\n${DesktopStorage.musicDir}",
            action = {
                Button(onClick = onSync, enabled = !syncing) {
                    Icon(Icons.Filled.Sync, null, Modifier.size(18.dp))
                    Text("  Sincronizar con el móvil")
                }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(greeting(), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        Row {
            PlayCard(
                title = "Aleatorio",
                subtitle = "${songs.size} canciones",
                icon = Icons.Filled.Shuffle,
                onClick = { player.playShuffled(songs) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(16.dp))
            PlayCard(
                title = "Seguir por donde ibas",
                subtitle = "Lo último que añadiste",
                icon = Icons.Filled.PlayArrow,
                onClick = { player.playQueue(songs, 0) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            "Añadidas recientemente",
            Modifier.padding(top = 28.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        val recent = songs.take(15)
        LazyColumn {
            itemsIndexed(recent, key = { _, s -> s.id }) { index, song ->
                SongRow(song, playing = false, onClick = { player.playQueue(recent, index) })
            }
        }
    }
}

@Composable
private fun PlayCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(onClick = onClick, modifier = modifier.height(96.dp)) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun greeting(): String {
    val hour = java.time.LocalTime.now().hour
    return when {
        hour < 6 -> "Buenas noches"
        hour < 13 -> "Buenos días"
        hour < 21 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}

// ---- Biblioteca ----

@Composable
fun LibraryScreen(songs: List<Song>, player: DesktopPlayer, currentId: String?) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) ||
                it.album.orEmpty().contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Buscar en la biblioteca") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        if (songs.isEmpty()) {
            EmptyState("Biblioteca vacía", "Sincroniza con el móvil desde Ajustes")
            return
        }
        if (filtered.isEmpty()) {
            EmptyState("Sin resultados", "Nada coincide con «$query»")
            return
        }

        LazyColumn {
            itemsIndexed(filtered, key = { _, s -> s.id }) { index, song ->
                SongRow(song, playing = song.id == currentId, onClick = { player.playQueue(filtered, index) })
            }
        }
    }
}

// ---- Playlists ----

@Composable
fun PlaylistsScreen(dao: MusicDao, player: DesktopPlayer, currentId: String?) {
    var open by remember { mutableStateOf<Playlist?>(null) }
    val playlists by dao.observePlaylists().collectAsState(emptyList())

    open?.let { playlist ->
        PlaylistDetail(playlist, dao, player, currentId, onBack = { open = null })
        return
    }

    if (playlists.isEmpty()) {
        EmptyState("No hay playlists", "Las que tengas en el móvil llegarán al sincronizar")
        return
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Playlists", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LazyColumn {
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(playlist, dao, onClick = { open = playlist })
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, dao: MusicDao, onClick: () -> Unit) {
    val artPaths by dao.observePlaylistArt(playlist.id).collectAsState(emptyList())
    val size by dao.observePlaylistSize(playlist.id).collectAsState(0)

    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistCover(artPaths, 56.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("$size canciones", playlist.description?.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    dao: MusicDao,
    player: DesktopPlayer,
    currentId: String?,
    onBack: () -> Unit,
) {
    val songs by dao.observePlaylistSongsOrdered(playlist.id).collectAsState(emptyList())

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Atrás") }
            Text(playlist.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            if (songs.isNotEmpty()) {
                Button(onClick = { player.playQueue(songs, 0) }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Text("  Reproducir")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (songs.isEmpty()) {
            EmptyState("Playlist vacía", "No tiene canciones todavía")
            return
        }
        LazyColumn {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(song, playing = song.id == currentId, onClick = { player.playQueue(songs, index) })
            }
        }
    }
}

// ---- Ajustes ----

@Composable
fun SettingsScreen(
    songs: List<Song>,
    syncing: Boolean,
    syncStatus: String?,
    onSync: () -> Unit,
    update: DesktopUpdater.UpdateInfo?,
    onUpdate: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)

        Text("Móvil", Modifier.padding(top = 24.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        Text(
            "Enciende «Compartir con el PC» en los ajustes del móvil y pulsa aquí. " +
                "Se encuentran solos en la red local.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSync, enabled = !syncing) {
                Icon(Icons.Filled.Sync, null, Modifier.size(18.dp))
                Text("  Sincronizar ahora")
            }
            if (syncing) {
                CircularProgressIndicator(Modifier.padding(start = 16.dp).size(20.dp), strokeWidth = 2.dp)
            }
            syncStatus?.let {
                Text("  $it", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Biblioteca", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        InfoRow("Canciones", "${songs.size}")
        InfoRow("Carpeta", DesktopStorage.dataDir.absolutePath)

        Text("Aplicación", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        InfoRow("Versión", DesktopUpdater.currentVersion)
        if (update != null) {
            Button(onClick = onUpdate, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Text("  Actualizar a ${update.version}")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
