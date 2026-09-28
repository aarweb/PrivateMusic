package com.aar.privatemusic.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.ui.components.rememberSongListState
import com.aar.privatemusic.data.BrowseCollection
import com.aar.privatemusic.data.LibraryBrowseLogic
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.ui.components.ArtImage
import com.aar.privatemusic.ui.components.SongOverflowMenu
import com.aar.privatemusic.ui.components.SongRow
import java.io.File

private data class BrowseSelection(
    val title: String,
    val songIds: List<String>,
)

private data class BrowseSection(
    val id: String,
    val title: String,
    val groups: List<BrowseCollection>,
    val emptyMessage: String,
)

@Composable
fun LibraryBrowseScreen(
    app: PrivateMusicApp,
    songs: List<Song>,
    query: String,
) {
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val allSongs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val enrichment by app.genreEnricher.state.collectAsState()
    val musicRoot = app.repository.musicDir.absolutePath

    val genreGroups = remember(songs) { LibraryBrowseLogic.genres(songs) }
    val decadeGroups = remember(songs) { LibraryBrowseLogic.decades(songs) }
    val yearGroups = remember(songs) { LibraryBrowseLogic.years(songs) }
    val folderGroups = remember(songs, musicRoot) {
        LibraryBrowseLogic.folders(songs, musicRoot)
    }
    val normalizedQuery = remember(query) {
        com.aar.privatemusic.lyrics.LyricSearch.normalize(query.trim())
    }

    fun filterGroups(groups: List<BrowseCollection>): List<BrowseCollection> =
        if (normalizedQuery.isBlank()) groups else groups.filter { group ->
            com.aar.privatemusic.lyrics.LyricSearch.normalize(group.label)
                .contains(normalizedQuery) ||
                group.subtitle?.let { subtitle ->
                    com.aar.privatemusic.lyrics.LyricSearch.normalize(subtitle)
                }
                    ?.contains(normalizedQuery) == true
        }

    val sections = remember(
        genreGroups, decadeGroups, yearGroups, folderGroups, normalizedQuery,
    ) {
        listOf(
            BrowseSection(
                id = "genres",
                title = "Géneros",
                groups = filterGroups(genreGroups),
                emptyMessage = "Aún no hay géneros disponibles.",
            ),
            BrowseSection(
                id = "decades",
                title = "Décadas",
                groups = filterGroups(decadeGroups),
                emptyMessage = "No hay canciones con una década identificada.",
            ),
            BrowseSection(
                id = "years",
                title = "Años",
                groups = filterGroups(yearGroups),
                emptyMessage = "No hay canciones con el año identificado.",
            ),
            BrowseSection(
                id = "folders",
                title = "Carpetas",
                groups = filterGroups(folderGroups),
                emptyMessage = "No hay carpetas de música que mostrar.",
            ),
        )
    }
    var selected by remember { mutableStateOf<BrowseSelection?>(null) }
    BackHandler(enabled = selected != null) { selected = null }

    val selection = selected
    if (selection != null) {
        val songsById = remember(songs) { songs.associateBy { it.id } }
        val detailSongs = remember(selection, songsById) {
            selection.songIds.mapNotNull(songsById::get)
        }
        BrowseGroupDetail(
            app = app,
            title = selection.title,
            songs = detailSongs,
            nowPlayingId = nowPlaying?.songId,
            onBack = { selected = null },
        )
        return
    }

    val pendingGenres = remember(allSongs) { allSongs.count { it.genresJson == null } }
    val libraryHasGenreGroups = remember(allSongs) {
        LibraryBrowseLogic.genres(allSongs).isNotEmpty()
    }
    val hasVisibleGroups = sections.any { it.groups.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        item(key = "genre-enrichment") {
            GenreEnrichmentPanel(
                running = enrichment.running,
                total = enrichment.total,
                done = enrichment.done,
                genresFound = enrichment.genresFound,
                failed = enrichment.failed,
                pending = pendingGenres,
                songCount = allSongs.size,
                hasGenreGroups = libraryHasGenreGroups,
                onStart = { app.genreEnricher.startMissing() },
            )
        }

        if (songs.isEmpty()) {
            item(key = "browse-empty-library") {
                BrowseMessage(
                    if (query.isBlank()) {
                        "No hay canciones en esta vista. Si está activo el filtro Favoritas, " +
                            "prueba a desactivarlo."
                    } else {
                        "No hay canciones con las que formar grupos."
                    }
                )
            }
        } else if (!hasVisibleGroups && normalizedQuery.isNotBlank()) {
            item(key = "browse-empty-query") {
                BrowseMessage("No hay grupos que coincidan con “${query.trim()}”.")
            }
        }

        sections.forEach { section ->
            if (normalizedQuery.isBlank() || section.groups.isNotEmpty()) {
                item(key = "section-${section.id}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 20.dp,
                            bottom = 6.dp,
                        ),
                    )
                }
                if (section.groups.isEmpty()) {
                    item(key = "section-${section.id}-empty") {
                        Text(
                            section.emptyMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(
                        section.groups,
                        key = { group -> "${section.id}-${group.key}" },
                        contentType = { "browse-group" },
                    ) { group ->
                        BrowseGroupRow(group) {
                            selected = BrowseSelection(
                                title = group.label,
                                songIds = group.songs.map { it.id },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreEnrichmentPanel(
    running: Boolean,
    total: Int,
    done: Int,
    genresFound: Int,
    failed: Int,
    pending: Int,
    songCount: Int,
    hasGenreGroups: Boolean,
    onStart: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Completar géneros", style = MaterialTheme.typography.titleSmall)
            Text(
                "MusicBrainz puede completar los géneros que faltan. Se consulta solo " +
                    "cuando tú lo pides y el progreso se puede reanudar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) {
                val progress = if (total > 0) done.toFloat() / total else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "$done de $total · $genresFound con géneros" +
                        if (failed > 0) " · $failed fallos" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
            } else {
                if (songCount == 0) {
                    Text(
                        "No hay canciones en esta vista para completar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (pending > 0) {
                    Button(onClick = onStart) {
                        Text("Completar géneros ($pending)")
                    }
                } else {
                    Text(
                        if (hasGenreGroups) "Los géneros están completos."
                        else "Se revisaron las canciones, pero MusicBrainz no aportó géneros.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failed > 0) {
                    Text(
                        "$failed canciones no se pudieron completar; puedes reintentarlas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseGroupRow(group: BrowseCollection, onClick: () -> Unit) {
    val coverSong = group.songs.firstOrNull { !it.artPath.isNullOrBlank() }
        ?: group.songs.firstOrNull()
    val cover = coverSong?.artPath?.let(::File)
        ?: coverSong?.thumbnailUrl?.takeIf { it.isNotBlank() }
    val duration = group.songs.fold(0L) { total, song -> total + song.durationSec }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Abrir ${group.label}", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(cover, 52.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                group.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            group.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${group.songs.size} ${if (group.songs.size == 1) "canción" else "canciones"}" +
                    " · ${formatBrowseDuration(duration)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrowseGroupDetail(
    app: PrivateMusicApp,
    title: String,
    songs: List<Song>,
    nowPlayingId: String?,
    onBack: () -> Unit,
) {
    val duration = remember(songs) {
        songs.fold(0L) { total, song -> total + song.durationSec }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver a Explorar")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${songs.size} ${if (songs.size == 1) "canción" else "canciones"}" +
                        " · ${formatBrowseDuration(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { app.playerController.playQueue(songs, 0) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Text("Todo", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(
                onClick = { app.playerController.playQueue(songs.shuffled(), 0) },
                enabled = songs.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Text("Aleatorio", modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BrowseMessage("Este grupo ya no contiene canciones.")
            }
        } else {
            val songListState = rememberSongListState(songs.isNotEmpty())
            LazyColumn(Modifier.fillMaxSize(), state = songListState) {
                items(songs, key = { it.id }, contentType = { "browse-song" }) { song ->
                    SongRow(
                        song = song,
                        isCurrent = song.id == nowPlayingId,
                        onClick = {
                            val index = songs.indexOfFirst { it.id == song.id }
                            app.playerController.playQueue(songs, index.coerceAtLeast(0))
                        },
                        onSwipeToQueue = {
                            app.playerController.addToQueue(song)
                            com.aar.privatemusic.util.Feedback.show("Añadida a la cola")
                        },
                        trailing = { SongOverflowMenu(app, song) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowseMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

private fun formatBrowseDuration(totalSec: Long): String {
    val hours = totalSec / 3_600
    val minutes = (totalSec % 3_600) / 60
    return if (hours > 0) "${hours} h ${minutes} min" else "${minutes} min"
}
