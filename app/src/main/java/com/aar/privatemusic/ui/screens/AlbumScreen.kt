package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.rememberSongListState
import com.aar.privatemusic.data.AlbumLogic
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.EmptyState
import com.aar.privatemusic.ui.components.SongOverflowMenu
import com.aar.privatemusic.ui.components.SongRow
import com.aar.privatemusic.util.Feedback
import java.io.File

@Composable
fun AlbumScreen(
    app: PrivateMusicApp,
    albumTitle: String,
    albumArtist: String,
    onBack: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    val allSongs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val songs = remember(allSongs, albumTitle, albumArtist) {
        AlbumLogic.tracksFor(allSongs, albumTitle, albumArtist)
    }
    val album = remember(songs) { AlbumLogic.group(songs).firstOrNull() }
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val coverSong = songs.firstOrNull { !it.artPath.isNullOrBlank() } ?: songs.firstOrNull()
    val cover = coverSong?.artPath?.let(::File)
        ?: coverSong?.thumbnailUrl?.takeIf { it.isNotBlank() }

    val songListState = rememberSongListState(songs.isNotEmpty())
    LazyColumn(Modifier.fillMaxSize(), state = songListState) {
        item(key = "album-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                ArtImage(cover, 104.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        albumTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        albumArtist,
                        modifier = Modifier.clickable(
                            enabled = albumArtist.isNotBlank(),
                            onClick = { onOpenArtist(albumArtist) },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            album?.year?.let { add(it.toString()) }
                            add("${songs.size} canciones")
                            add(formatAlbumDuration(album?.durationSec ?: songs.sumOf { it.durationSec }))
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Button(
                    onClick = { app.playerController.playQueue(songs, 0) },
                    enabled = songs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Reproducir álbum")
                    Text("Reproducir", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = { app.playerController.playQueueShuffled(songs) },
                    enabled = songs.isNotEmpty(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Reproducir álbum aleatoriamente")
                    Text("Aleatorio", Modifier.padding(start = 4.dp))
                }
            }
        }

        if (songs.isEmpty()) {
            item(key = "album-empty") {
                EmptyState("Este álbum ya no tiene canciones en la biblioteca.")
            }
        } else {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    supportingText = song.trackNumber
                        ?.takeIf { it > 0 }
                        ?.let { "Pista $it" },
                    onClick = { app.playerController.playQueue(songs, index) },
                    onSwipeToQueue = {
                        app.playerController.addToQueue(song)
                        Feedback.show("Añadida a la cola")
                    },
                    trailing = { SongOverflowMenu(app, song) },
                )
            }
        }
    }
}

private fun formatAlbumDuration(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return if (hours > 0) "${hours} h ${minutes} min" else "${minutes} min"
}
