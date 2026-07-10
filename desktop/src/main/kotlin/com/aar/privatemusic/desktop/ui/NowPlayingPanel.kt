package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.desktop.lyrics.LyricsState
import com.aar.privatemusic.desktop.player.DesktopPlayer
import java.io.File

private enum class PanelTab(val label: String) {
    SONANDO("Sonando"), LETRA("Letra"), COLA("Cola"), INFO("Info")
}

/**
 * Lo que suena, en un panel a la derecha. No es una pantalla: la barra de
 * navegación y la de reproducción siguen ahí, y se puede seguir buscando la
 * siguiente canción mientras se mira la carátula.
 *
 * La cola vive aquí, en su pestaña, en vez de ser una pantalla aparte a la que
 * hay que navegar y de la que hay que volver.
 */
@Composable
fun NowPlayingPanel(song: Song, player: DesktopPlayer, lyrics: LyricsState, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(PanelTab.SONANDO) }

    Surface(Modifier.width(340.dp).fillMaxHeight(), tonalElevation = 2.dp) {
        Box(Modifier.fillMaxSize()) {
            UltraBlurBackground(song.artPath)

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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

                TabRow(tab.ordinal, containerColor = Color.Transparent) {
                    PanelTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                when (tab) {
                    PanelTab.SONANDO -> NowPlayingTab(song)
                    PanelTab.LETRA -> LyricsPane(lyrics, player.positionMs)
                    PanelTab.COLA -> QueueTab(player)
                    PanelTab.INFO -> InfoTab(song)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTab(song: Song) {
    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
        )
        if (badges.isNotEmpty()) {
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                badges.forEach { badge ->
                    AssistChip(onClick = {}, label = { Text(badge, style = MaterialTheme.typography.labelSmall) })
                }
            }
        }
    }
}

/**
 * La cola, con "sonando" arriba y "a continuación" debajo. Se reordena con
 * flechas, no arrastrando: un drag-and-drop en `LazyColumn` es mucho código y
 * mucho borde, y aquí la operación real es "sube esto una posición".
 */
@Composable
private fun QueueTab(player: DesktopPlayer) {
    val queue by player.queue.collectAsState()
    val index by player.index.collectAsState()

    if (queue.isEmpty()) {
        EmptyState("Cola vacía", "Reproduce algo y aparecerá aquí")
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        // La clave lleva la posición porque la misma canción puede estar dos veces
        // en la cola: `song.id` solo no distingue una copia de la otra.
        itemsIndexed(queue, key = { position, song -> "$position|${song.id}" }) { position, song ->
            if (position == index) {
                Text(
                    "SONANDO AHORA",
                    Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (position == index + 1) {
                Text(
                    "A CONTINUACIÓN",
                    Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            QueueRow(
                song = song,
                playing = position == index,
                onClick = { player.playAt(position) },
                onUp = { player.moveInQueue(position, position - 1) },
                onDown = { player.moveInQueue(position, position + 1) },
                onRemove = { player.removeFromQueue(position) },
                canGoUp = position > 0,
                canGoDown = position < queue.lastIndex,
            )
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    playing: Boolean,
    onClick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    canGoUp: Boolean,
    canGoDown: Boolean,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(
                if (playing) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(song.artPath, 40.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onUp, Modifier.size(24.dp), enabled = canGoUp) {
            Icon(Icons.Filled.ArrowUpward, "Subir", Modifier.size(14.dp))
        }
        IconButton(onDown, Modifier.size(24.dp), enabled = canGoDown) {
            Icon(Icons.Filled.ArrowDownward, "Bajar", Modifier.size(14.dp))
        }
        IconButton(onRemove, Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, "Quitar de la cola", Modifier.size(14.dp))
        }
    }
}

/** Todo lo que la base sabe de la canción. Aquí no se esconde nada. */
@Composable
private fun InfoTab(song: Song) {
    val file = remember(song.filePath) { File(song.filePath) }
    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        InfoLine("Título", song.title)
        InfoLine("Artista", song.artist)
        song.album?.takeIf { it.isNotBlank() }?.let { InfoLine("Álbum", it) }
        song.albumArtist?.takeIf { it.isNotBlank() }?.let { InfoLine("Artista del álbum", it) }
        song.year?.let { InfoLine("Año", "$it") }
        InfoLine("Duración", formatDuration(song.durationSec * 1000L))
        Spacer(Modifier.height(12.dp))
        InfoLine("Códec", song.codec ?: "—")
        InfoLine("Bitrate", song.bitrateKbps?.let { "$it kbps" } ?: "—")
        InfoLine("Frecuencia", song.sampleRateHz?.let { "$it Hz" } ?: "—")
        InfoLine("Sonoridad", song.loudnessDb?.let { "%.1f dB".format(it) } ?: "—")
        InfoLine("BPM", song.bpm?.let { "${it.toInt()}" } ?: "—")
        InfoLine("Tonalidad", song.camelot ?: "—")
        Spacer(Modifier.height(12.dp))
        InfoLine("Tamaño", if (file.exists()) "%.1f MB".format(file.length() / 1048576.0) else "—")
        InfoLine("Fichero", song.filePath)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            Modifier.width(120.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}
