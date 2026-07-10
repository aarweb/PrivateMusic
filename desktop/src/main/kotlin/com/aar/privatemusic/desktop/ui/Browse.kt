package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song

/** Un artista o un álbum, derivados de las canciones: no hay tablas propias. */
data class Grouping(val name: String, val songs: List<Song>) {
    val artPath: String? get() = songs.firstNotNullOfOrNull { it.artPath }
    val subtitle: String get() = "${songs.size} ${if (songs.size == 1) "canción" else "canciones"}"
}

fun artistsOf(songs: List<Song>): List<Grouping> =
    songs.groupBy { it.artist.trim().ifBlank { "Desconocido" } }
        .map { (name, list) -> Grouping(name, list) }
        .sortedByDescending { it.songs.size }

fun albumsOf(songs: List<Song>): List<Grouping> =
    songs.filter { !it.album.isNullOrBlank() }
        .groupBy { it.album!!.trim() }
        .map { (name, list) -> Grouping(name, list.sortedBy { it.trackNumber ?: Int.MAX_VALUE }) }
        .sortedBy { it.name.lowercase() }

// ---- Artistas ----

@Composable
fun ArtistsScreen(songs: List<Song>, onOpen: (String) -> Unit) {
    val artists = remember(songs) { artistsOf(songs) }
    if (artists.isEmpty()) {
        EmptyState("Sin artistas", "Trae tu biblioteca del móvil desde Ajustes")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Artistas",
            Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists, key = { it.name }) { artist ->
                GridCard(artist.name, artist.subtitle, artist.artPath, round = true) { onOpen(artist.name) }
            }
        }
    }
}

// ---- Álbumes ----

@Composable
fun AlbumsScreen(songs: List<Song>, onOpen: (String) -> Unit) {
    val albums = remember(songs) { albumsOf(songs) }
    if (albums.isEmpty()) {
        EmptyState(
            "Sin álbumes",
            "Ninguna de tus canciones tiene álbum. Identifica sus metadatos en el móvil " +
                "y sincroniza: aquí aparecerán solas.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Álbumes",
            Modifier.padding(start = 24.dp, top = 24.dp, bottom = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        // Nunca mentir por omisión: si 188 de 203 canciones no tienen álbum, la
        // rejilla no representa la biblioteca y hay que decirlo.
        val orphans = remember(songs) { songs.count { it.album.isNullOrBlank() } }
        if (orphans > 0) {
            Text(
                "$orphans canciones no tienen álbum y no aparecen aquí.",
                Modifier.padding(start = 24.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(albums, key = { it.name }) { album ->
                val year = album.songs.firstNotNullOfOrNull { it.year }
                val subtitle = listOfNotNull(album.songs.first().artist, year?.toString()).joinToString(" · ")
                GridCard(album.name, subtitle, album.artPath) { onOpen(album.name) }
            }
        }
    }
}

// ---- Detalle (sirve para artista y para álbum) ----

/**
 * Cabecera con carátula grande y botones, y debajo la tabla. La cabecera
 * **scrollea con el contenido** en vez de quedarse fija: una portada de 200 dp
 * clavada arriba se come media ventana en cuanto quieres leer la lista.
 */
@Composable
fun GroupingDetail(
    grouping: Grouping,
    round: Boolean,
    density: RowDensity,
    currentId: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    actions: SongActions,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (round) {
                Box(Modifier.size(160.dp).clip(CircleShape)) { Cover(grouping.artPath, 160.dp) }
            } else {
                Cover(grouping.artPath, 160.dp)
            }
            Column(Modifier.padding(start = 24.dp)) {
                Text(
                    grouping.name,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val minutes = grouping.songs.sumOf { it.durationSec } / 60
                Text(
                    "${grouping.subtitle} · $minutes min",
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 16.dp)) {
                    Button({ onPlay(grouping.songs, 0) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text("  Reproducir")
                    }
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton({ onShuffle(grouping.songs) }) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                        Text("  Aleatorio")
                    }
                }
            }
        }
        SongTable(
            songs = grouping.songs,
            density = density,
            currentId = currentId,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            actions = actions,
        )
    }
}

// ---- Favoritas ----

@Composable
fun FavoritesScreen(
    songs: List<Song>,
    density: RowDensity,
    currentId: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    actions: SongActions,
) {
    val favorites = remember(songs) { songs.filter { it.isFavorite } }
    if (favorites.isEmpty()) {
        EmptyState("Sin favoritas", "Pulsa el corazón de cualquier canción y aparecerá aquí")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Favoritas",
            Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        SongTable(favorites, density, currentId, onPlay, onToggleFavorite, actions = actions)
    }
}

// ---- Tarjeta de la rejilla ----

@Composable
private fun GridCard(
    title: String,
    subtitle: String,
    artPath: String?,
    round: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        Modifier.clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (round) {
            Box(Modifier.size(140.dp).clip(CircleShape)) { Cover(artPath, 140.dp) }
        } else {
            Cover(artPath, 140.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
