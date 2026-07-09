package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.ui.components.ArtImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** Landing screen: greeting, daily mix hero, carousels and shortcuts. */
@Composable
fun HomeScreen(
    app: PrivateMusicApp,
    onOpenSearch: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenStats: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val recent by app.repository.observeRecentlyPlayed(12).collectAsState(initial = emptyList())
    val added by app.repository.observeRecentlyAdded().collectAsState(initial = emptyList())
    val playlists by app.repository.observePlaylists().collectAsState(initial = emptyList())
    val songs by app.repository.observeSongs().collectAsState(initial = emptyList())
    val nowPlaying by app.playerController.nowPlaying.collectAsState()
    val scope = rememberCoroutineScope()

    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 6..13 -> "Buenos días"
        in 14..20 -> "Buenas tardes"
        else -> "Buenas noches"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            greeting,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp),
        )
        Text(
            "¿Qué escuchamos hoy?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        )

        if (songs.isEmpty()) {
            // Empty state: guide to the search tab instead of a blank wall.
            Surface(
                onClick = onOpenSearch,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp),
                    )
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(
                            "Tu biblioteca está vacía",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Busca cualquier canción y descárgala a máxima calidad",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            return@Column
        }

        // --- Mix de hoy (hero) ---
        // Cuatro carátulas reales de la biblioteca dan al hero un aire propio,
        // en vez de un gradiente plano. Si aún no hay 4, cae al círculo de play.
        val mixArts = remember(recent, added, songs) {
            (recent + added + songs).distinctBy { it.id }
                .mapNotNull { it.artPath }.distinct().take(4)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        )
                    )
                )
                .clickable {
                    scope.launch {
                        val mix = app.repository.buildDailyMix()
                        if (mix.isNotEmpty()) app.playerController.playQueue(mix, 0)
                    }
                },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "MIX DE HOY",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        "Tu mezcla diaria",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        "Se renueva cada día con tu historial y favoritas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Box(contentAlignment = Alignment.Center) {
                    if (mixArts.size == 4) {
                        Column(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))) {
                            Row(Modifier.weight(1f)) {
                                MosaicCell(mixArts[0]); MosaicCell(mixArts[1])
                            }
                            Row(Modifier.weight(1f)) {
                                MosaicCell(mixArts[2]); MosaicCell(mixArts[3])
                            }
                        }
                        // Velo oscuro para que el botón de play resalte sobre las portadas.
                        Box(
                            Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Reproducir Mix de hoy",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }

        // --- Reproducir aleatorio (toda la biblioteca) ---
        Surface(
            onClick = { app.playerController.playQueueShuffled(songs) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Reproducir aleatorio",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Text(
                    "${songs.size} canciones",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }

        // --- Seguir escuchando ---
        if (recent.isNotEmpty()) {
            HomeSection("Seguir escuchando")
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(recent, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        highlighted = song.id == nowPlaying?.songId,
                        onClick = {
                            val index = recent.indexOfFirst { it.id == song.id }
                            app.playerController.playQueue(recent, index.coerceAtLeast(0))
                        },
                    )
                }
            }
        }

        // --- Añadidas recientemente ---
        val freshest = added.take(12)
        if (freshest.isNotEmpty()) {
            HomeSection("Añadidas recientemente")
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(freshest, key = { it.id }) { song ->
                    SongCard(
                        song = song,
                        highlighted = song.id == nowPlaying?.songId,
                        onClick = {
                            val index = freshest.indexOfFirst { it.id == song.id }
                            app.playerController.playQueue(freshest, index.coerceAtLeast(0))
                        },
                    )
                }
            }
        }

        // --- Tus playlists ---
        val sortedPlaylists = playlists.sortedByDescending { it.isPinned }
        if (sortedPlaylists.isNotEmpty()) {
            HomeSection("Tus playlists")
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sortedPlaylists, key = { it.id }) { pl ->
                    Column(
                        Modifier
                            .width(120.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpenPlaylist(pl.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val art by app.repository.observePlaylistArt(pl.id)
                            .collectAsState(initial = emptyList())
                        com.aar.privatemusic.ui.components.PlaylistCover(pl.coverPath, art, 120.dp)
                        Text(
                            pl.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // --- Accesos rápidos ---
        HomeSection("Atajos")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShortcutCard(
                title = "Favoritas",
                icon = { Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.weight(1f),
                onClick = onOpenLibrary,
            )
            ShortcutCard(
                title = "Tu Recap",
                icon = { Icon(Icons.Filled.Timeline, null, tint = MaterialTheme.colorScheme.tertiary) },
                modifier = Modifier.weight(1f),
                onClick = onOpenStats,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Una celda cuadrada del mosaico del hero (la mitad de un lado 2×2). */
@Composable
private fun RowScope.MosaicCell(artPath: String) {
    AsyncImage(
        model = File(artPath),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.weight(1f).fillMaxHeight(),
    )
}

@Composable
private fun HomeSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 10.dp),
    )
}

@Composable
private fun SongCard(song: Song, highlighted: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Box {
            if (song.artPath != null) {
                ArtImage(File(song.artPath), 132.dp)
            } else {
                Box(
                    Modifier
                        .size(132.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
            if (highlighted) {
                Box(
                    Modifier
                        .size(132.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Text(
            song.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShortcutCard(
    title: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
