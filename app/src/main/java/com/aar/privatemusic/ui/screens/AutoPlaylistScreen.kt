package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.SongRow

enum class AutoPlaylistType(val route: String, val title: String) {
    MOST_PLAYED("mostplayed", "Más escuchadas"),
    FORGOTTEN("forgotten", "Olvidadas"),
    RECENTLY_ADDED("recent", "Añadidas recientemente"),
    SEASONAL("seasonal", "Top de tu temporada"),
}

@Composable
fun AutoPlaylistScreen(app: PrivateMusicApp, type: AutoPlaylistType) {
    val flow = remember(type) {
        when (type) {
            AutoPlaylistType.MOST_PLAYED -> app.repository.observeMostPlayed()
            AutoPlaylistType.FORGOTTEN -> app.repository.observeForgotten()
            AutoPlaylistType.RECENTLY_ADDED -> app.repository.observeRecentlyAdded()
            AutoPlaylistType.SEASONAL -> app.repository.observeSeasonalTop()
        }
    }
    val songs by flow.collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Text(
            type.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        if (songs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Button(
                    onClick = { app.playerController.playQueue(songs, 0) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Reproducir", Modifier.padding(start = 6.dp))
                }
                OutlinedButton(
                    onClick = { app.playerController.playQueueShuffled(songs) },
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Aleatorio", Modifier.padding(start = 6.dp))
                }
            }
        }
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nada por aquí todavía.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    onClick = { app.playerController.playQueue(songs, index) },
                )
            }
        }
    }
}
