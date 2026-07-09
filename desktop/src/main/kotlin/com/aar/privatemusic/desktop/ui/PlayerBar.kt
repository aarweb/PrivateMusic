package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.player.DesktopPlayer
import com.aar.privatemusic.desktop.player.RepeatMode

/**
 * La barra de reproducción de un escritorio: fija, a lo ancho de la ventana, y
 * nunca desaparece. Tres zonas de igual peso — qué suena, los controles, y los
 * mandos secundarios — como hacen Spotify, Apple Music, YouTube Music y Feishin.
 *
 * Lo que había antes era un mini-reproductor de móvil: una barra de progreso de
 * 2 dp que no se ve ni se puede arrastrar, y ni volumen ni cola.
 */
@Composable
fun PlayerBar(
    song: Song?,
    preview: DesktopPlayer.Preview?,
    player: DesktopPlayer,
    panelOpen: Boolean,
    onTogglePanel: () -> Unit,
) {
    val isPlaying by player.isPlaying.collectAsState()
    val positionMs by player.positionMs.collectAsState()
    val durationMs by player.durationMs.collectAsState()
    val shuffle by player.shuffle.collectAsState()
    val repeat by player.repeat.collectAsState()
    val volume by player.volume.collectAsState()

    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Qué suena. Clicar abre el panel: la carátula es el mando más grande.
            Row(
                Modifier.weight(1f).clickable(enabled = song != null, onClick = onTogglePanel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (song != null) Cover(song.artPath, 56.dp) else RemoteCover(preview?.coverUrl, 56.dp)
                // `fill = false`: el texto ocupa lo que necesita y encoge si no
                // cabe, pero no estira la fila. Con `weight(1f)` a secas, el
                // corazón salía disparado al centro de la ventana.
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                    Text(
                        song?.title ?: preview?.title.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Una preescucha no es una canción tuya: dilo, no la disfraces.
                        if (song != null) song.artist else "Preescucha · ${preview?.artist.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (song != null) {
                    IconButton(player::toggleFavorite, Modifier.size(36.dp)) {
                        Icon(
                            if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favorita",
                            Modifier.size(20.dp),
                            tint = if (song.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Los controles, y debajo la posición: un Slider de verdad, arrastrable.
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Durante una preescucha no hay cola que recorrer ni orden que barajar.
                val hasQueue = song != null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(player::toggleShuffle, Modifier.size(32.dp), enabled = hasQueue) {
                        Icon(
                            Icons.Filled.Shuffle,
                            "Aleatorio",
                            Modifier.size(18.dp),
                            tint = if (shuffle) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(player::previous, Modifier.size(36.dp), enabled = hasQueue) {
                        Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(24.dp))
                    }
                    FilledIconButton(
                        player::togglePlayPause,
                        Modifier.padding(horizontal = 6.dp).size(40.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (isPlaying) "Pausa" else "Reproducir",
                            Modifier.size(22.dp),
                        )
                    }
                    IconButton(player::next, Modifier.size(36.dp), enabled = hasQueue) {
                        Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(24.dp))
                    }
                    IconButton(player::cycleRepeat, Modifier.size(32.dp), enabled = hasQueue) {
                        Icon(
                            if (repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            "Repetir",
                            Modifier.size(18.dp),
                            tint = if (repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                        onValueChange = { if (durationMs > 0) player.seekTo((it * durationMs).toLong()) },
                        enabled = durationMs > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Mandos secundarios, alineados a la derecha.
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onTogglePanel, enabled = song != null) {
                    Icon(
                        Icons.Filled.QueueMusic,
                        "Panel de reproducción",
                        tint = if (panelOpen) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (volume == 0) Icons.Filled.VolumeMute else Icons.Filled.VolumeUp,
                    "Volumen",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = volume / 100f,
                    onValueChange = { player.setVolume((it * 100).toInt()) },
                    modifier = Modifier.width(110.dp).padding(start = 8.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
