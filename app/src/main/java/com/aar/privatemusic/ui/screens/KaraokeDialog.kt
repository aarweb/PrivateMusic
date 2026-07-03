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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.KaraokeSeparator

/**
 * Karaoke flow: downloads the separation model once, extracts the
 * instrumental of [song] and plays it. Closing the dialog cancels the work.
 */
@Composable
fun KaraokeDialog(app: PrivateMusicApp, song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    val cached = remember(song.id) {
        KaraokeSeparator.instrumentalFile(app.repository.musicDir, song.id).length() > 1000
    }
    val modelReady = remember { KaraokeSeparator.isModelReady(context) }

    if (started) {
        LaunchedEffect(song.id) {
            failed = false
            if (!KaraokeSeparator.isModelReady(context)) {
                status = "Descargando modelo de IA (36 MB)…"
                if (!KaraokeSeparator.downloadModel(context) { progress = it }) {
                    failed = true
                    status = "No se pudo descargar el modelo. Comprueba tu conexión."
                    return@LaunchedEffect
                }
            }
            status = "Separando la voz… (puede tardar un par de minutos)"
            progress = 0
            val file = KaraokeSeparator.separate(context, song, app.repository.musicDir) { progress = it }
            if (file != null) {
                app.playerController.playKaraoke(song, file)
                onDismiss()
            } else {
                failed = true
                status = "No se pudo separar la voz de esta canción."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Karaoke") },
        text = {
            Column {
                if (!started) {
                    Text(
                        if (cached) "Ya hay una versión sin voz de esta canción. ¿Reproducirla?"
                        else buildString {
                            append("Se creará una versión instrumental de “${song.title}” con IA local, sin conexión.")
                            if (!modelReady) append("\n\nLa primera vez se descarga un modelo de 36 MB.")
                            append("\n\nLa letra sincronizada seguirá disponible en el reproductor para cantar encima.")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (!failed) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        )
                        Text(
                            "$progress %",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!started) {
                TextButton(onClick = {
                    if (cached) {
                        val file = KaraokeSeparator.instrumentalFile(app.repository.musicDir, song.id)
                        app.playerController.playKaraoke(song, file)
                        onDismiss()
                    } else {
                        started = true
                    }
                }) { Text(if (cached) "Reproducir" else "Crear y reproducir") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
