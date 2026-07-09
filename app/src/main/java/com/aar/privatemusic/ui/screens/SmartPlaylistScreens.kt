package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.ui.components.SongRow
import kotlinx.coroutines.launch

/** Dialog to create a rule-based playlist. */
@Composable
fun CreateSmartPlaylistDialog(app: PrivateMusicApp, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var artistContains by remember { mutableStateOf("") }
    var onlyFavorites by remember { mutableStateOf(false) }
    var minPlays by remember { mutableStateOf("") }
    var addedWithinDays by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlist inteligente") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = artistContains,
                    onValueChange = { artistContains = it },
                    label = { Text("Artista contiene… (opcional)") },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = minPlays,
                    onValueChange = { minPlays = it.filter { c -> c.isDigit() } },
                    label = { Text("Mínimo de reproducciones (opcional)") },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = addedWithinDays,
                    onValueChange = { addedWithinDays = it.filter { c -> c.isDigit() } },
                    label = { Text("Añadida en los últimos N días (opcional)") },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = onlyFavorites, onCheckedChange = { onlyFavorites = it })
                    Text("Solo favoritas")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        scope.launch {
                            app.repository.createSmartPlaylist(
                                SmartPlaylist(
                                    name = trimmed,
                                    artistContains = artistContains.trim().ifBlank { null },
                                    onlyFavorites = onlyFavorites,
                                    minPlays = minPlays.toIntOrNull() ?: 0,
                                    addedWithinDays = addedWithinDays.toIntOrNull() ?: 0,
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    }
                    onDismiss()
                },
            ) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun SmartPlaylistDetailScreen(app: PrivateMusicApp, smartPlaylistId: Long) {
    val sp by app.repository.observeSmartPlaylist(smartPlaylistId).collectAsState(initial = null)
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val counts by app.repository.observePlayCounts().collectAsState(initial = emptyList())

    val playlist = sp ?: return
    val countMap = remember(counts) { counts.associate { it.songId to it.plays } }
    val matching = remember(playlist, songs, countMap) {
        app.repository.evaluateSmartPlaylist(playlist, songs, countMap)
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            playlist.name,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            buildString {
                append("Reglas: ")
                val rules = buildList {
                    playlist.artistContains?.let { add("artista contiene \"$it\"") }
                    if (playlist.onlyFavorites) add("solo favoritas")
                    if (playlist.minPlays > 0) add("≥${playlist.minPlays} reproducciones")
                    if (playlist.addedWithinDays > 0) add("añadida en ${playlist.addedWithinDays} días")
                }
                append(if (rules.isEmpty()) "todas las canciones" else rules.joinToString(" · "))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (matching.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ninguna canción cumple las reglas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }

        com.aar.privatemusic.ui.components.GeneratedPlaylistActions(
            app = app,
            songs = matching,
            defaultName = playlist.name,
            modifier = Modifier.padding(top = 8.dp),
        )

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(matching, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    onClick = { app.playerController.playQueue(matching, index) },
                )
            }
        }
    }
}
