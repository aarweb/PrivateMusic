package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song

/** Carátula, o una nota musical cuando no hay. */
@Composable
fun Cover(artPath: String?, size: Dp, modifier: Modifier = Modifier) {
    val image = rememberArt(artPath)
    val shape = if (size > 120.dp) MaterialTheme.shapes.medium else MaterialTheme.shapes.small
    Box(
        modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Filled.MusicNote,
                null,
                Modifier.size(size / 2.5f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Mosaico de hasta cuatro carátulas, como en las playlists del móvil. */
@Composable
fun PlaylistCover(artPaths: List<String>, size: Dp) {
    if (artPaths.size < 4) {
        Cover(artPaths.firstOrNull(), size)
        return
    }
    Box(Modifier.size(size).clip(MaterialTheme.shapes.small)) {
        Column {
            Row {
                Cover(artPaths[0], size / 2); Cover(artPaths[1], size / 2)
            }
            Row {
                Cover(artPaths[2], size / 2); Cover(artPaths[3], size / 2)
            }
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.artPath, 48.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (song.isFavorite) {
            Icon(
                Icons.Filled.Favorite,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            formatDuration(song.durationSec * 1000L),
            Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

/** Lo que la app dice cuando no tiene nada que enseñar. Nunca una pantalla en blanco. */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            null,
            Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            subtitle,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action?.let { Spacer(Modifier.height(20.dp)); it() }
    }
}

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
