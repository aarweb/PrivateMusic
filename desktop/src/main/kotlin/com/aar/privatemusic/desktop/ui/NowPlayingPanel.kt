package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song

/**
 * Lo que suena, en un panel a la derecha. No es una pantalla: la barra de
 * navegación y la de reproducción siguen ahí, y se puede seguir buscando la
 * siguiente canción mientras se mira la carátula.
 *
 * La versión anterior era una pantalla completa que hacía `return` y borraba
 * toda la navegación. Ninguna de las seis apps de escritorio analizadas lo hace.
 */
@Composable
fun NowPlayingPanel(song: Song, onClose: () -> Unit) {
    Surface(
        Modifier.width(320.dp).fillMaxHeight(),
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sonando ahora",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClose, Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, "Cerrar el panel", Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Cover(song.artPath, 280.dp)

            Text(
                song.title,
                Modifier.padding(top = 20.dp),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            song.album?.takeIf { it.isNotBlank() }?.let { album ->
                Text(
                    listOfNotNull(album, song.year?.toString()).joinToString(" · "),
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // La calidad que ya conocemos del fichero. No hay que ir a buscarla.
            val badges = listOfNotNull(
                song.codec?.uppercase(),
                song.bitrateKbps?.let { "$it kbps" },
                song.sampleRateHz?.let { "${it / 1000} kHz" },
                song.bpm?.let { "${it.toInt()} BPM" },
                song.camelot,
            )
            if (badges.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    badges.take(3).forEach { badge ->
                        AssistChip(onClick = {}, label = { Text(badge, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            }
        }
    }
}
