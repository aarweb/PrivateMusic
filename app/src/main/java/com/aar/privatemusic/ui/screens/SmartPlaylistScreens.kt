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

@Composable
fun SmartPlaylistDetailScreen(app: PrivateMusicApp, smartPlaylistId: Long) {
    val sp by app.repository.observeSmartPlaylist(smartPlaylistId).collectAsState(initial = null)
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val counts by app.repository.observePlayCounts().collectAsState(initial = emptyList())
    val lastPlays by app.repository.observeLastPlayed().collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf(false) }

    val playlist = sp ?: return
    val countMap = remember(counts) { counts.associate { it.songId to it.plays } }
    val lastMap = remember(lastPlays) { lastPlays.associate { it.songId to it.lastPlayed } }
    val matching = remember(playlist, songs, countMap, lastMap) {
        app.repository.evaluateSmartPlaylist(playlist, songs, countMap, lastMap)
    }

    if (editing) {
        SmartPlaylistEditorDialog(app, existing = playlist, onDismiss = { editing = false })
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editing = true }) { Text("Editar reglas") }
        }
        Text(
            "Reglas: " + com.aar.privatemusic.data.SmartRuleEngine.describe(
                com.aar.privatemusic.data.SmartRuleEngine.rulesOf(playlist),
            ),
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
