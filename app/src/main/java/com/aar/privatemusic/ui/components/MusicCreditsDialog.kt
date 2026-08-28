package com.aar.privatemusic.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.metadata.CreditGroup
import com.aar.privatemusic.metadata.CreditsLoad
import com.aar.privatemusic.metadata.MusicCredit
import com.aar.privatemusic.metadata.MusicCredits
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Ficha de créditos reutilizable. Cada petición tiene una generación propia:
 * aunque un cliente de red no cooperase con la cancelación, una respuesta vieja
 * nunca puede sustituir los datos de la canción que el usuario está mirando.
 */
@Composable
fun MusicCreditsDialog(
    app: PrivateMusicApp,
    song: Song,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSongId by rememberUpdatedState(song.id)
    var generation by remember(song.id) { mutableLongStateOf(0L) }
    var loadJob by remember(song.id) { mutableStateOf<Job?>(null) }
    var result by remember(song.id) { mutableStateOf<CreditsLoad?>(null) }
    var loading by remember(song.id) { mutableStateOf(false) }
    var error by remember(song.id) { mutableStateOf<String?>(null) }

    fun requestLoad(forceRefresh: Boolean) {
        val requestedSong = song
        val requestedId = requestedSong.id
        val requestGeneration = ++generation
        loadJob?.cancel()
        loading = true
        error = null
        loadJob = scope.launch {
            app.creditsService.load(requestedSong, forceRefresh).fold(
                onSuccess = { loaded ->
                    if (currentSongId == requestedId && generation == requestGeneration) {
                        result = loaded
                        loading = false
                    }
                },
                onFailure = { failure ->
                    if (currentSongId == requestedId && generation == requestGeneration) {
                        error = failure.message?.takeIf { it.isNotBlank() }
                            ?: "No se pudieron cargar los créditos"
                        loading = false
                    }
                },
            )
        }
    }

    LaunchedEffect(song.id) { requestLoad(forceRefresh = false) }
    DisposableEffect(song.id) {
        onDispose { loadJob?.cancel() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Créditos y conexiones") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CreditsSongHeader(song)

                if (loading && result == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                            .semantics { contentDescription = "Cargando créditos" },
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                result?.let { loaded ->
                    if (loading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "Actualizando créditos…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (loaded.fromCache) {
                        Text(
                            "Guardado para uso sin conexión",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CreditsContent(loaded.credits)
                    if (loaded.credits.recordingMbid.isNotBlank()) {
                        TextButton(
                            onClick = {
                                val url = "https://musicbrainz.org/recording/${
                                    Uri.encode(loaded.credits.recordingMbid)
                                }"
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure {
                                    com.aar.privatemusic.util.Feedback.show(
                                        "No se pudo abrir MusicBrainz"
                                    )
                                }
                            },
                        ) {
                            Text("Ver en MusicBrainz")
                        }
                    }
                }

                error?.let { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { requestLoad(forceRefresh = true) },
                            enabled = !loading,
                        ) {
                            Text("Reintentar")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        dismissButton = {
            TextButton(
                onClick = { requestLoad(forceRefresh = true) },
                enabled = !loading,
            ) {
                Text("Actualizar")
            }
        },
    )
}

@Composable
private fun CreditsSongHeader(song: Song) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.semantics { contentDescription = "Portada de ${song.title}" }) {
            ArtImage(song.artPath?.let(::File) ?: song.thumbnailUrl, 56.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CreditsContent(credits: MusicCredits) {
    val sections = listOf(
        CreditGroup.WRITING to "Composición y letra",
        CreditGroup.PRODUCTION to "Producción",
        CreditGroup.PERFORMANCE to "Intérpretes y músicos",
        CreditGroup.TECHNICAL to "Técnica y estudio",
        CreditGroup.OTHER to "Otros créditos",
    )
    val hasNamedCredits = credits.credits.isNotEmpty()

    if (!hasNamedCredits) {
        Text(
            "MusicBrainz no incluye créditos para esta grabación. La ficha puede estar incompleta.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    sections.forEach { (group, title) ->
        val entries = credits.credits.filter { it.group == group }
        if (entries.isNotEmpty()) {
            CreditsSection(title, entries)
        }
    }

    if (credits.connections.isNotEmpty()) {
        SimpleSection("Conexiones") {
            credits.connections.forEachIndexed { index, connection ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        connection.kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(connection.title, style = MaterialTheme.typography.bodyLarge)
                    connection.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                        Text(
                            artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (index != credits.connections.lastIndex) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }

    if (credits.genres.isNotEmpty()) {
        SimpleSection("Géneros") {
            Text(
                credits.genres.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (credits.isrcs.isNotEmpty()) {
        SimpleSection("Identificadores ISRC") {
            Text(
                credits.isrcs.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun CreditsSection(title: String, entries: List<MusicCredit>) {
    SimpleSection(title) {
        entries.forEachIndexed { index, credit ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    credit.role,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(credit.name, style = MaterialTheme.typography.bodyLarge)
                if (credit.details.isNotEmpty()) {
                    Text(
                        credit.details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (index != entries.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun SimpleSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}
