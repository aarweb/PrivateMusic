package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.RuleContext
import com.aar.privatemusic.data.SmartRuleEngine
import com.aar.privatemusic.data.db.LastPlay
import com.aar.privatemusic.data.db.PlayCount
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.data.db.Song

/**
 * Una playlist inteligente no guarda canciones: guarda una regla, y la regla se
 * evalúa aquí, contra la biblioteca del PC y su historial de escuchas.
 *
 * En el escritorio son de sólo lectura: se crean y se editan en el móvil, y la
 * sincronización las trae. Un editor de reglas anidadas es una pantalla entera y
 * no es lo que falta hoy.
 */
@Composable
fun SmartPlaylistDetail(
    smart: SmartPlaylist,
    songs: List<Song>,
    playCounts: List<PlayCount>,
    lastPlayed: List<LastPlay>,
    density: RowDensity,
    currentId: String?,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    actions: SongActions,
) {
    val rules = remember(smart) { SmartRuleEngine.rulesOf(smart) }
    val description = remember(rules) { SmartRuleEngine.describe(rules) }

    // `evaluate` puede barajar (orden aleatorio). Fuera de `remember` la lista se
    // reordenaría en cada recomposición y la canción bajo el ratón cambiaría.
    val matches = remember(rules, songs, playCounts, lastPlayed) {
        SmartRuleEngine.evaluate(
            rules,
            songs,
            RuleContext(
                playCounts = playCounts.associate { it.songId to it.plays },
                lastPlayed = lastPlayed.associate { it.songId to it.lastPlayed },
            ),
        )
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
            Text(
                smart.name,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                description,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val minutes = matches.sumOf { it.durationSec } / 60
            Text(
                "${matches.size} ${if (matches.size == 1) "canción" else "canciones"} · $minutes min",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (matches.isNotEmpty()) {
                Row(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                    Button({ onPlay(matches, 0) }) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text("  Reproducir")
                    }
                    Spacer(Modifier.size(12.dp))
                    FilledTonalButton({ onShuffle(matches) }) {
                        Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp))
                        Text("  Aleatorio")
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            EmptyState("Ninguna canción cumple la regla", description)
            return
        }
        SongTable(matches, density, currentId, onPlay, onToggleFavorite, actions = actions)
    }
}
