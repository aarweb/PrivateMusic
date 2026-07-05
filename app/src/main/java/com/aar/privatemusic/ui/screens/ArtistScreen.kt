package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.SongRow
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.launch
import java.io.File

/** All songs by one artist, with play-all / shuffle / sonic radio. */
@Composable
fun ArtistScreen(app: PrivateMusicApp, artistName: String) {
    val allSongs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val songs = remember(allSongs, artistName) {
        allSongs.filter { it.artist.equals(artistName, ignoreCase = true) }
    }
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtImage(songs.firstOrNull()?.artPath?.let { File(it) }, 72.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        artistName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${songs.size} canciones · ${songs.sumOf { it.durationSec } / 60} min",
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
                Button(
                    onClick = { app.playerController.playQueue(songs, 0) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Todo", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = { app.playerController.playQueueShuffled(songs) },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = null)
                    Text("Aleatorio", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val seed = songs.firstOrNull { it.sonicFeatures != null }
                            if (seed == null) {
                                Feedback.show("Aún no hay canciones analizadas de este artista")
                            } else {
                                val radio = app.repository.radioFor(seed)
                                if (radio.size > 1) {
                                    app.playerController.playQueue(radio, 0)
                                    Feedback.show("Radio de $artistName en marcha")
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Podcasts, contentDescription = null)
                    Text("Radio", Modifier.padding(start = 4.dp))
                }
            }
        }
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.id == nowPlaying?.songId,
                onClick = { app.playerController.playQueue(songs, index) },
            )
        }
    }
}
