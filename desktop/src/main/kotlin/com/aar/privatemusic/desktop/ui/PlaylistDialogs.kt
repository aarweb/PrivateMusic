package com.aar.privatemusic.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aar.privatemusic.data.db.Playlist
import com.aar.privatemusic.data.db.Song

/** Pide un nombre. Sirve para crear y para renombrar. */
@Composable
fun PlaylistNameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "Guardar",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Nombre") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()); onDismiss() },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/**
 * A qué playlist va la canción. Si no hay ninguna, el diálogo no se queda vacío
 * mirándote: ofrece crear la primera.
 */
@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    onPick: (Playlist) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir a playlist") },
        text = {
            Column {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onCreate(); onDismiss() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text("  Nueva playlist…", style = MaterialTheme.typography.bodyMedium)
                }
                if (playlists.isEmpty()) {
                    Text(
                        "Todavía no tienes ninguna.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(playlist); onDismiss() }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.QueueMusic, null, Modifier.size(18.dp))
                                Text(
                                    "  ${playlist.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/**
 * Borrar una playlist no se deshace, así que se enseña su nombre: confirmar a
 * ciegas es cómo se borra la equivocada.
 */
@Composable
fun ConfirmDeletePlaylistDialog(playlist: Playlist, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Borrar \"${playlist.name}\"?") },
        text = {
            Text(
                "La playlist desaparecerá también del móvil en la próxima " +
                    "sincronización. Las canciones no se borran.",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Borrar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}
