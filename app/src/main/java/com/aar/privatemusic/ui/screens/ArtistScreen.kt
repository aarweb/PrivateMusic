package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.AlbumCollection
import com.aar.privatemusic.data.ArtistLogic
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.EmptyState
import com.aar.privatemusic.ui.components.SongOverflowMenu
import com.aar.privatemusic.ui.components.SongRow
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.launch
import java.io.File

/** Ficha offline de artista con destacadas, discografía y catálogo completo. */
@Composable
fun ArtistScreen(
    app: PrivateMusicApp,
    artistName: String,
    onBack: () -> Unit = {},
    onOpenAlbum: (title: String, artist: String) -> Unit = { _, _ -> },
) {
    val allSongs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val songs = remember(allSongs, artistName) { ArtistLogic.songsFor(allSongs, artistName) }
    val albums = remember(allSongs, artistName) { ArtistLogic.albumsFor(allSongs, artistName) }
    val playCounts by produceState<Map<String, Int>>(
        initialValue = emptyMap(),
        key1 = songs.map { it.id },
    ) {
        value = runCatching { app.repository.playCounts() }.getOrDefault(emptyMap())
    }
    val featured = remember(songs, playCounts) {
        ArtistLogic.featuredSongs(songs, playCounts, limit = 5)
    }
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()
    val coverSong = songs.firstOrNull { !it.artPath.isNullOrBlank() }
        ?: songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
        ?: songs.firstOrNull()
    val cover = coverSong?.artPath?.let(::File)
        ?: coverSong?.thumbnailUrl?.takeIf { it.isNotBlank() }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "artist-header") {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
                ArtImage(cover, 104.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        artistName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildList {
                            if (albums.isNotEmpty()) add("${albums.size} álbumes")
                            add("${songs.size} canciones")
                            add(formatArtistDuration(songs.sumOf { it.durationSec }))
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Button(
                    onClick = { app.playerController.playQueue(songs, 0) },
                    enabled = songs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("Todo", Modifier.padding(start = 4.dp))
                }
                OutlinedButton(
                    onClick = { app.playerController.playQueueShuffled(songs) },
                    enabled = songs.isNotEmpty(),
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
                    enabled = songs.isNotEmpty(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.Podcasts, contentDescription = null)
                    Text("Radio", Modifier.padding(start = 4.dp))
                }
            }
        }

        if (songs.isEmpty()) {
            item(key = "artist-empty") {
                EmptyState("Este artista ya no tiene canciones en la biblioteca.")
            }
        } else {
            item(key = "featured-title") { ArtistSectionTitle("Canciones destacadas") }
            items(featured, key = { "featured-${it.id}" }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    supportingText = playCounts[song.id]
                        ?.takeIf { it > 0 }
                        ?.let { count -> "$count ${if (count == 1) "reproducción" else "reproducciones"}" },
                    onClick = {
                        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        app.playerController.playQueue(songs, index)
                    },
                    trailing = { SongOverflowMenu(app, song) },
                )
            }

            if (albums.isNotEmpty()) {
                item(key = "albums-title") { ArtistSectionTitle("Álbumes") }
                item(key = "albums-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(albums, key = { "${it.key.artist}\u0000${it.key.title}" }) { album ->
                            ArtistAlbumCard(album, onOpenAlbum)
                        }
                    }
                }
            }

            item(key = "all-title") { ArtistSectionTitle("Todas las canciones") }
            itemsIndexed(songs, key = { _, song -> "all-${song.id}" }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlaying?.songId,
                    onClick = { app.playerController.playQueue(songs, index) },
                    trailing = { SongOverflowMenu(app, song) },
                )
            }
        }
    }
}

@Composable
private fun ArtistSectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ArtistAlbumCard(
    album: AlbumCollection,
    onOpenAlbum: (title: String, artist: String) -> Unit,
) {
    val coverSong = album.songs.firstOrNull { !it.artPath.isNullOrBlank() }
        ?: album.songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
        ?: album.songs.firstOrNull()
    val cover = coverSong?.artPath?.let(::File)
        ?: coverSong?.thumbnailUrl?.takeIf { it.isNotBlank() }

    Card(
        onClick = { onOpenAlbum(album.key.title, album.key.artist) },
        modifier = Modifier.width(148.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            ArtImage(cover, 128.dp)
            Text(
                text = album.key.title,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    album.year?.let { add(it.toString()) }
                    add("${album.songs.size} canciones")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatArtistDuration(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    return if (hours > 0) "${hours} h ${minutes} min" else "${minutes} min"
}
