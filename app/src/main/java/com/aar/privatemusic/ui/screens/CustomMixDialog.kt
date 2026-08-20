package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.MixPromptParser
import com.aar.privatemusic.data.SmartRuleEngine
import com.aar.privatemusic.data.db.SmartPlaylist
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Chips que añaden frases al texto: lo que se pulsa es lo que el parser lee. */
private val CHIPS: List<Pair<String, String>> = listOf(
    "Tranquilo" to "tranquilo", "Enérgico" to "enérgico", "Bailable" to "bailable",
    "Alegre" to "alegre", "Triste" to "triste", "Instrumental" to "sin voz", "Con voz" to "con voz",
    "Rápido" to "rápido", "Lento" to "lento",
    "80s" to "de los 80", "90s" to "de los 90", "2000s" to "de los 2000", "2010s" to "de los 2010", "2020s" to "de los 2020",
    "Favoritas" to "favoritas", "Sin sonar en 30 días" to "que no haya sonado en un mes",
    "Nunca escuchadas" to "que nunca he escuchado", "Para mezclar" to "en la misma tonalidad",
)

/**
 * "Mix a medida": una frase en español (y/o chips) → cola de ~40 canciones de
 * tu biblioteca, sin nube. La respuesta offline a las playlists por texto de
 * los servicios de streaming: determinista, y enseña lo que ha entendido.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomMixDialog(app: PrivateMusicApp, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var count by remember { mutableStateOf<Int?>(null) }
    var moodPending by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val prompt = (listOf(text.trim()) + CHIPS.filter { it.first in picked }.map { it.second })
        .filter { it.isNotBlank() }.joinToString(" ")
    val parsed = remember(prompt) { MixPromptParser.parse(prompt) }

    // Cuenta en vivo: es una consulta, no un análisis; con un pequeño retardo
    // para no consultar en cada tecla.
    LaunchedEffect(prompt) {
        delay(300)
        if (prompt.isBlank()) { count = null; return@LaunchedEffect }
        count = app.repository.countCustomMix(parsed)
        moodPending = app.musicDao.countMissingMood()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Mix a medida") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Describe lo que te apetece") },
                    placeholder = { Text("algo tranquilo de los 90 que no haya sonado en un mes") },
                    minLines = 2,
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CHIPS.forEach { (label, _) ->
                        FilterChip(
                            selected = label in picked,
                            onClick = { picked = if (label in picked) picked - label else picked + label },
                            label = { Text(label) },
                        )
                    }
                }
                if (parsed.summary.isNotEmpty()) {
                    Text(
                        "Entendido: " + parsed.summary.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (parsed.ignored.isNotEmpty()) {
                    Text(
                        "No he entendido: " + parsed.ignored.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                count?.let { n ->
                    Text(
                        when {
                            n == 0 -> "Ninguna canción cumple todo eso"
                            n == 1 -> "1 canción"
                            else -> "$n canciones"
                        } + if (moodPending > 0) " · analizando tu biblioteca ($moodPending pendientes)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = prompt.isNotBlank() && !busy && (count ?: 1) > 0,
                onClick = {
                    busy = true
                    scope.launch {
                        val mix = app.repository.buildCustomMix(parsed)
                        busy = false
                        if (mix.songs.isEmpty()) {
                            Feedback.show("Ninguna canción cumple todo eso")
                        } else {
                            app.playerController.playQueueInOrder(mix.songs)
                            Feedback.show("Mix a medida: ${mix.songs.size} canciones")
                            onDismiss()
                        }
                    }
                },
            ) { Text("Reproducir") }
        },
        dismissButton = {
            TextButton(
                enabled = prompt.isNotBlank() && !busy && !parsed.rules.root.isEmpty,
                onClick = {
                    scope.launch {
                        val name = prompt.replaceFirstChar { it.uppercase() }.take(40)
                        app.repository.createSmartPlaylist(
                            SmartPlaylist(
                                name = name,
                                artistContains = null,
                                onlyFavorites = false,
                                minPlays = 0,
                                addedWithinDays = 0,
                                createdAt = System.currentTimeMillis(),
                                rulesJson = SmartRuleEngine.toJson(parsed.rules.copy(limit = 40)),
                            )
                        )
                        Feedback.show("Playlist inteligente «$name» guardada")
                        onDismiss()
                    }
                },
            ) { Text("Guardar como inteligente") }
        },
    )
}
