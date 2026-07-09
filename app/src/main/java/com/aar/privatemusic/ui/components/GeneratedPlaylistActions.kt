package com.aar.privatemusic.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.PrivateMusicApp
import com.aar.privatemusic.data.db.Song
import com.aar.privatemusic.util.Feedback
import kotlinx.coroutines.launch

/**
 * Acciones de las playlists generadas (automáticas e inteligentes): reproducir,
 * aleatorio y **congelarlas como playlist normal**. Estas listas se recalculan
 * cada vez, así que "Guardar" toma una foto de las canciones que cumplen hoy
 * las reglas; es la única forma de retener un Mix que salió bien.
 */
@Composable
fun GeneratedPlaylistActions(
    app: PrivateMusicApp,
    songs: List<Song>,
    defaultName: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    if (songs.isEmpty()) return

    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Button(
            onClick = { app.playerController.playQueue(songs, 0) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text("Reproducir", Modifier.padding(start = 6.dp))
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    app.playerController.playQueueInOrder(app.repository.shuffleFewerRepeats(songs))
                }
            },
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Text("Aleatorio", Modifier.padding(start = 6.dp))
        }
        OutlinedButton(onClick = { saving = true }, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Filled.Save, contentDescription = "Guardar como playlist")
        }
    }

    if (saving) {
        SaveAsPlaylistDialog(
            defaultName = defaultName,
            count = songs.size,
            onDismiss = { saving = false },
            onConfirm = { name ->
                saving = false
                scope.launch {
                    val id = app.repository.createPlaylist(name)
                    val added = app.repository.addSongsToPlaylist(id, songs.map { it.id })
                    Feedback.show("\"$name\" creada con $added canciones")
                }
            },
        )
    }
}

@Composable
private fun SaveAsPlaylistDialog(
    defaultName: String,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar como playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nombre") },
                supportingText = { Text("Se copiarán las $count canciones actuales") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
