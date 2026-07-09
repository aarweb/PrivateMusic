package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.ui.components.ArtImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

/** Landing screen: greeting, play cards, carousels and shortcuts. */
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
        // Un saludo, no una portada de revista: cada dp de cabecera es un dp
        // menos de música visible, y aquí abajo sólo caben cinco filas.
        Text(
            greeting,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
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

        // --- Empezar a sonar ---
        // El héroe "Mix de hoy" ocupaba 122 dp para no decir nada que su propia
        // fila no diga: el mix sigue a un toque, y ahora entra contenido real
        // sin desplazar. La otra puerta al mix está en la pestaña Playlists.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayCard(
                title = "Aleatorio",
                subtitle = "${songs.size} ${if (songs.size == 1) "canción" else "canciones"}",
                icon = Icons.Filled.Shuffle,
                container = MaterialTheme.colorScheme.secondaryContainer,
                onContainer = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
                onClick = { app.playerController.playQueueShuffled(songs) },
            )
            PlayCard(
                title = "Mix de hoy",
                subtitle = "Se renueva a diario",
                icon = Icons.Filled.PlayArrow,
                container = MaterialTheme.colorScheme.primaryContainer,
                onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        val mix = app.repository.buildDailyMix()
                        if (mix.isNotEmpty()) app.playerController.playQueue(mix, 0)
                    }
                },
            )
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
        // Sin las que ya salen arriba: con una biblioteca pequeña, "recientes" y
        // "añadidas" son casi la misma lista, y repetir carátulas parece relleno.
        val freshest = remember(added, recent) {
            val seen = recent.map { it.id }.toSet()
            added.filterNot { it.id in seen }.take(12)
        }
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

/** Las dos puertas de entrada a la música: aleatorio y mix del día. */
@Composable
private fun PlayCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = container,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = onContainer)
            Column(Modifier.padding(start = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainer)
                // Sin alpha: el contador a 0.7 daba 3,8:1 sobre el contenedor,
                // por debajo del mínimo legible.
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
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
