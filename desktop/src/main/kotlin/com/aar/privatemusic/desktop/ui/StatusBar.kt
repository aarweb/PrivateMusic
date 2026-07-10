package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Song

/**
 * La línea de estado de foobar2000: cuánta música hay y qué está sonando de
 * verdad. Sale gratis — el códec, el bitrate y la frecuencia están en la base
 * desde que se importó la canción — y a cambio la ventana deja de tener un
 * borde inferior muerto.
 */
@Composable
fun StatusBar(songs: List<Song>, current: Song?) {
    val parts = buildList {
        add("${songs.size} ${if (songs.size == 1) "canción" else "canciones"}")
        val totalSec = songs.sumOf { it.durationSec }
        if (totalSec > 0) add(formatLongDuration(totalSec))
        current?.let { song ->
            val quality = listOfNotNull(
                song.codec?.uppercase(),
                song.bitrateKbps?.let { "$it kbps" },
                song.sampleRateHz?.let { "%.1f kHz".format(it / 1000f) },
            )
            if (quality.isNotEmpty()) add(quality.joinToString(" "))
        }
    }

    Surface(tonalElevation = 1.dp) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                parts.joinToString("  ·  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "1 h 42 min" o "42 min". Los segundos aquí no le importan a nadie. */
private fun formatLongDuration(totalSec: Int): String {
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return if (hours > 0) "$hours h $minutes min" else "$minutes min"
}
