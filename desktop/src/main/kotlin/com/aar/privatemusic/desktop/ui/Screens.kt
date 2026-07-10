package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.FilledTonalButton
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
fun LibraryScreen(
    songs: List<Song>,
    player: DesktopPlayer,
    currentId: String?,
    density: RowDensity,
    actions: SongActions,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) ||
                it.album.orEmpty().contains(query, true)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filtrar") },
                singleLine = true,
                modifier = Modifier.width(280.dp),
            )
        }

        if (songs.isEmpty()) {
            EmptyState("Biblioteca vacía", "Sincroniza con el móvil desde Ajustes")
            return
        }
        if (filtered.isEmpty()) {
            EmptyState("Sin resultados", "Nada coincide con «$query»")
            return
        }
        SongTable(
            songs = filtered,
            density = density,
            currentId = currentId,
            onPlay = { list, index -> player.playQueue(list, index) },
            onToggleFavorite = player::toggleFavoriteOf,
            actions = actions,
        )
    }
}

// ---- Playlists ----

@Composable
fun PlaylistDetail(
    playlist: Playlist,
    dao: MusicDao,
    player: DesktopPlayer,
    currentId: String?,
    density: RowDensity,
    actions: SongActions,
) {
    val songs by dao.observePlaylistSongsOrdered(playlist.id).collectAsState(emptyList())
    val artPaths by dao.observePlaylistArt(playlist.id).collectAsState(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            PlaylistCover(artPaths, 160.dp)
            Column(Modifier.padding(start = 24.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        "${songs.size} canciones",
                        playlist.description?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (songs.isNotEmpty()) {
                    Row(Modifier.padding(top = 16.dp)) {
                        Button(onClick = { player.playQueue(songs, 0) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Text("  Reproducir")
                        }
                        Spacer(Modifier.width(12.dp))
                        FilledTonalButton(onClick = { player.playShuffled(songs) }) {
                            Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                            Text("  Aleatorio")
                        }
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            EmptyState("Playlist vacía", "No tiene canciones todavía")
            return
        }
        SongTable(
            songs = songs,
            density = density,
            currentId = currentId,
            onPlay = { list, index -> player.playQueue(list, index) },
            onToggleFavorite = player::toggleFavoriteOf,
            actions = actions,
        )
    }
}

// ---- Ajustes ----

@Composable
fun SettingsScreen(
    songs: List<Song>,
    settings: com.aar.privatemusic.desktop.DesktopSettings,
    engine: com.aar.privatemusic.desktop.audio.AudioEngine,
    syncing: Boolean,
    syncStatus: String?,
    onSync: () -> Unit,
    onSyncAddress: (String) -> Unit,
    update: DesktopUpdater.UpdateInfo?,
    onUpdate: () -> Unit,
) {
    // Con el ecualizador desplegado esto mide más que cualquier ventana.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
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

        // mDNS no llega a todas las redes: las de invitados aíslan a los clientes,
        // una VPN se lleva el multicast, y algún cortafuegos lo tira. La dirección
        // a mano siempre funciona; el móvil la enseña bajo su interruptor.
        var address by remember { mutableStateOf("") }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("…o escribe la dirección del móvil") },
                placeholder = { Text("192.168.1.152") },
                singleLine = true,
                modifier = Modifier.width(320.dp),
            )
            Button(
                onClick = { onSyncAddress(address.trim()) },
                enabled = !syncing && address.isNotBlank(),
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("Sincronizar") }
        }

        PlaybackSettings(settings, engine)

        AppearanceSettings(settings)

        DeezerSettings(settings)

        Text("Biblioteca", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        InfoRow("Canciones", "${songs.size}")
        InfoRow("Carpeta", DesktopStorage.dataDir.absolutePath)

        Text("Aplicación", Modifier.padding(top = 28.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
        InfoRow("Versión", DesktopUpdater.currentVersion)

        val autoUpdate by settings.autoUpdate.collectAsState()
        SettingSwitch(
            "Actualizar automáticamente",
            "Al abrir la app descarga la versión nueva, y la instala al cerrarla. " +
                "Nunca corta la música a medias.",
            autoUpdate,
        ) { settings.setAutoUpdate(it) }

        if (update != null) {
            Button(onClick = onUpdate, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                Text("  Actualizar a ${update.version}")
            }
        }
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
