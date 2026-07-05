package com.aar.privatemusic.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.KaraokeManager
import com.aar.privatemusic.util.KaraokeSeparator

/**
 * Karaoke flow. The heavy work lives in [KaraokeManager] (app-scoped), so
 * closing this dialog or rotating does NOT lose progress; "Cancelar" while
 * running cancels explicitly.
 */
@Composable
fun KaraokeDialog(app: PrivateMusicApp, song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by KaraokeManager.stateFor(song.id).collectAsState()

    val cached = remember(song.id) {
        KaraokeSeparator.instrumentalFileFor(context, app.repository.musicDir, song.id).length() > 1000
    }
    val modelReady = remember { KaraokeSeparator.isModelReady(context) }
    val hqEngine = remember { KaraokeSeparator.engine(context) == "mdx" }
    val tooLong = song.durationSec > KaraokeSeparator.MAX_DURATION_SEC

    // When the manager finishes while the dialog is open, play and close.
    LaunchedEffect(state.file) {
        state.file?.let { file ->
            app.playerController.playKaraoke(song, file)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Karaoke") },
        text = {
            Column {
                when {
                    state.running -> {
                        Text(state.status, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { state.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                        Text(
                            "${state.progress} % — puedes cerrar este diálogo, seguirá en segundo plano",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    state.failed -> Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    cached -> Text(
                        "Ya hay una versión sin voz de esta canción. ¿Reproducirla?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    tooLong -> Text(
                        "Esta canción dura más de 15 minutos: demasiado larga para separar la voz en el móvil.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> Text(
                        buildString {
                            append("Se creará una versión instrumental de “${song.title}” con IA local, sin conexión.")
                            if (!modelReady) append(
                                if (hqEngine) "\n\nLa primera vez se descarga un modelo de 67 MB (motor de calidad)."
                                else "\n\nLa primera vez se descarga un modelo de 36 MB."
                            )
                            append("\n\nLa letra sincronizada seguirá disponible en el reproductor para cantar encima.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            when {
                state.running -> {}
                cached -> TextButton(onClick = {
                    val file = KaraokeSeparator.instrumentalFileFor(context, app.repository.musicDir, song.id)
                    app.playerController.playKaraoke(song, file)
                    onDismiss()
                }) { Text("Reproducir") }
                !tooLong -> TextButton(onClick = {
                    KaraokeManager.start(context, song, app.repository.musicDir)
                }) { Text(if (state.failed) "Reintentar" else "Crear y reproducir") }
            }
        },
        dismissButton = {
            if (state.running) {
                TextButton(onClick = {
                    KaraokeManager.cancel(song.id)
                    onDismiss()
                }) { Text("Cancelar separación") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        },
    )
}
