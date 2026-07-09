package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.player.DesktopPlayer

@Composable
fun PlayerScreen(song: Song, player: DesktopPlayer, onClose: () -> Unit) {
    val isPlaying by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Cerrar") }
                Spacer(Modifier.weight(1f))
                song.codec?.let {
                    Text(
                        listOfNotNull(it.uppercase(), song.bitrateKbps?.let { k -> "$k kbps" })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Cover(song.artPath, 320.dp)

            Text(
                song.title,
                Modifier.padding(top = 28.dp),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                song.artist,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { if (durationMs > 0) player.seekTo((it * durationMs).toLong()) },
                enabled = durationMs > 0,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
            Row(Modifier.fillMaxWidth(0.7f), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Row(
                Modifier.padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                IconButton(player::toggleFavorite) {
                    Icon(
                        if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        "Favorita",
                        tint = if (song.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(12.dp))
                IconButton(player::previous) { Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(36.dp)) }
                FilledIconButton(player::togglePlayPause, Modifier.padding(horizontal = 12.dp).size(64.dp)) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (isPlaying) "Pausa" else "Reproducir",
                        Modifier.size(32.dp),
                    )
                }
                IconButton(player::next) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(36.dp)) }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
